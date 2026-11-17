package com.dilinkauto.desktop.video

import com.dilinkauto.protocol.BackoffPolicy
import com.dilinkauto.protocol.BlackScreenDetector
import com.dilinkauto.protocol.DecodeLivenessTracker
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.H264NalParser
import com.dilinkauto.protocol.VideoMsg
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端视频解码管线（Phase 1b）。
 *
 * 数据流：网络线程 [feed] → [FeedGate]（先 CONFIG、重建后等 IDR）→ [VideoStreamPipe]
 *        → 解码线程 `FFmpegFrameGrabber`（原始 H.264 码流模式）→ [onImage] 输出 BufferedImage。
 *
 * 解码器重建：`grab()` 出错或读到 EOF（会话尚未停止）时，丢弃旧管道，新建
 * gate + pipe + grabber，重放缓存的 CONFIG 并等下一个 IDR——与车机端 MediaCodec
 * flush 后的恢复策略一致。重建是**指数退避**的（500ms→8s）：坏码流下紧凑重试
 * 只会以 ~2 次/秒空转；连续重建到上限还会向上升级（audit D-M3）。
 *
 * 硬解（Phase 5b）：构造时给 [hwaccel]（如 `"d3d11va"`）就先用硬解跑；若
 * [HardwareDecodeWatchdog] 判定"起来了但一帧都出不来"，则本次运行内永久回退软解
 * ——硬解失败通常是驱动/机器不支持，重试也不会好。判定由**独立的定时线程**跑
 * （audit D-M2）：放在喂帧路径上时，卡在 `start()` 或手机停帧都不会触发。
 *
 * 持续黑屏（WIN-07）：与车机端共用 protocol-core 的 [BlackScreenDetector]，
 * 命中后经 [onSustainedBlackScreen] 交给上层（桌面端的选择是"重建一代会话"）。
 *
 * 职责边界（SRP）：本类只管"字节 → 图像"，不碰网络、不碰窗口；输出交给上层。
 * 线程约定：[feed] 由网络线程调用，[start]/[stop] 由控制线程调用，其余都在解码线程上。
 * [onImage] 的投递是**借用语义**（回调返回即失效），见该字段的 KDoc。
 */
class VideoDecodePipeline(
    /** 硬解加速器名（FFmpeg 的 `-hwaccel` 取值，如 "d3d11va"）；null = 纯软解。 */
    private val hwaccel: String? = null,
    /**
     * 硬解"起不来"的判定器。可注入是为了单测（与 [HardwareDecodeWatchdog] 自己
     * 的 `clock` 注入同理）：D-M2 的验证要看"检查跑没跑"，不能靠等 3 秒真实时间。
     */
    hardwareWatchdog: HardwareDecodeWatchdog = HardwareDecodeWatchdog(),
) {
    /**
     * 解码线程回调，每个解码出的画面调用一次。
     *
     * **契约**：回调返回前必须完成对这张图的消费（拷贝 / 编码 / 决定怎么画）——
     * `Java2DFrameConverter` 对所有帧**复用同一张** `BufferedImage`（javap 反汇编
     * javacv 1.5.10 证实），回调返回后它随时会被下一帧覆盖。要跨帧持有请自己拷贝
     * （audit WIN-05；`SwingVideoView.setFrame` 就是这么做的）。
     */
    var onImage: ((BufferedImage) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    /**
     * 码流**持续**黑屏时触发一次（audit WIN-07）。
     *
     * 判据与车机端共用 protocol-core 的 [BlackScreenDetector]：编码器对接近纯色的
     * 画面会输出极小的 I 帧，连续多个极小关键帧 + 持续满窗口 = 编码器/VirtualDisplay
     * 卡死（TCP 仍通，所以不会有断开事件）。在**喂帧线程**上触发，实现方不得阻塞。
     */
    var onSustainedBlackScreen: (() -> Unit)? = null

    /**
     * 解码线程被抛弃（join 超时且解阻塞无效）时触发一次（audit D-M1）。
     *
     * 上层据此知道这一代的解码器泄漏了 —— 桌面端的做法是打一条 WARN。不能在这里
     * 做更激进的动作（halt 进程之类）：泄漏一个守护线程优于杀死整个应用。
     */
    var onDecoderAbandoned: (() -> Unit)? = null

    /**
     * 解码器连续重建达到 [MAX_REBUILDS] 仍出不了画面时触发一次（audit D-M3）。
     *
     * 触发点是"持续解码失败以 ~2 次/秒无限重建"的出口：坏 CONFIG 重放会让
     * `grab()` 立刻抛，循环永远出不来，唯一症状是统计行的 `rebuilds=` 增长。
     * 上层据此把这一代判为没救（桌面端触发自动重连）。
     */
    var onDecodeStalled: (() -> Unit)? = null

    val framesDecoded = AtomicLong()
    val decoderRebuilds = AtomicLong()

    /** 网络线程收到的帧总数（含 CONFIG）；联调时与 framesDecoded 对照，定位瓶颈在接收侧还是解码侧。 */
    val framesFed = AtomicLong()

    /**
     * 是否已经向上升级过"解码器反复重建"（audit D-M3）。
     *
     * 升级必须**只发一次**：退避封顶后循环仍会以 8s/次继续重建兜底，若每次迭代都
     * 回调，上层（桌面端会自动重连，重连预算有限）会被重复触发，日志也会每 8s
     * 刷一条同样的内容。
     */
    private val escalated = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 收到的 CONFIG（SPS/PPS）次数。 */
    val configsFed = AtomicLong()

    /** 当前是否在用硬解（回退后变 false）。 */
    @Volatile
    var hardwareInUse: Boolean = hwaccel != null
        private set

    /** 硬解起不来的判定器；只有配置了 [hwaccel] 时才参与。 */
    private val hardwareWatchdog = hardwareWatchdog

    /**
     * 持续黑屏判定器。探测方法与车机端完全一致，只有持续窗口不同
     * （[BLACK_SCREEN_SUSTAIN_MS] 比 protocol-core 的默认值长，见那里的说明）。
     *
     * 升级闸门接了「解码是否还在出帧」：VD 内容合法为黑（空 launcher / 手机屏熄灭）
     * 时编码器会持续输出极小 I 帧，纯码流判据必然误报 —— 2026-10-17 MEIZU 实测：
     * 每个会话白烧 2 轮自动重连（~40s），把重连预算烧在没有故障的画面上。
     * 黑 + 仍在正常解码 = 内容黑，不重连；黑 + 解码停摆 = 管线卡死，才重连。
     */
    private val blackScreen = BlackScreenDetector().apply {
        setSustainWindow(BLACK_SCREEN_SUSTAIN_MS)
        decodingAliveCheck = { decodingAlive() }
    }

    /** [decodingAlive] 的存活判定器（audit D-M4，统一对齐 protocol-core / 车机端 DecodeLiveness）。 */
    private val liveness = DecodeLivenessTracker(DECODE_ALIVE_WINDOW_MS)

    /**
     * 解码线程是否还在出帧（最近 [DECODE_ALIVE_WINDOW_MS] 内 [framesDecoded] 有增长）。
     *
     * 只在黑屏满窗后才被调用（每帧一次），代价是读一个 AtomicLong + 一次时钟。
     * 首次采样时若还一帧都没解出来，按「活着」处理：预热期不该升级，等下一个
     * 窗口再判（真的停摆会在 2s 后翻成 false）。
     */
    private fun decodingAlive(): Boolean {
        val nowMs = System.nanoTime() / 1_000_000L
        return liveness.isAlive(framesDecoded.get(), nowMs)
    }

    /** 最近一次收到的 CONFIG，供解码器重建时重放。 */
    @Volatile
    private var config: ByteArray? = null

    @Volatile
    private var gate: FeedGate? = null

    @Volatile
    private var pipe: VideoStreamPipe? = null

    @Volatile
    private var running = false
    private var decodeThread: Thread? = null

    /** 硬解回退看门狗的定时线程（audit D-M2）：与有没有帧喂进来无关。 */
    private var fallbackTimer: Thread? = null

    /** 被 stop() 抛弃的解码线程计数（audit D-M1）：join 超时即 +1，泄漏不再无声无息。 */
    val abandonedDecoders = java.util.concurrent.atomic.AtomicLong()

    init {
        // 检测器的升级出口在构造时绑一次（不像车机端那样每帧设/清）：
        // 出口字段只有这一处写，喂帧路径就只剩一次 onFrame 调用。
        blackScreen.onSustainedBlackScreen = {
            log(
                "疑似持续黑屏：连续 ${blackScreen.streak()} 个微小关键帧且已持续 " +
                    "${blackScreen.sustainMs}ms（编码器或 VirtualDisplay 可能已卡死）",
            )
            onSustainedBlackScreen?.invoke()
        }
    }

    fun start() {
        if (running) return
        running = true
        liveness.reset()
        decodeThread = Thread({ decodeLoop() }, "VideoDecode").apply {
            isDaemon = true
            start()
        }
        // D-M2：硬解回退检查的独立定时线程（原来是 feed 内联的一步，见那里说明）。
        fallbackTimer = Thread({ fallbackLoop() }, "HwFallback").apply {
            isDaemon = true
            start()
        }
    }

    /** 硬解看门狗的定时循环（audit D-M2）：与有没有帧喂进来无关，到点就查。 */
    private fun fallbackLoop() {
        while (running) {
            runCatching { checkHardwareFallback() }
            try {
                Thread.sleep(HARDWARE_POLL_MS)
            } catch (_: InterruptedException) {
                return // stop() 打断
            }
        }
    }

    /** 网络线程调用，非阻塞：把收到的视频帧转交闸门。 */
    fun feed(frame: FrameCodec.Frame) {
        framesFed.incrementAndGet()
        val g = gate ?: return
        if (frame.messageType == VideoMsg.CONFIG) {
            configsFed.incrementAndGet()
            config = frame.payload
            g.onConfig(frame.payload)
        } else {
            val isKeyFrame = H264NalParser.isKeyFrame(frame.payload)
            g.onFrame(frame.payload, isKeyFrame)
            // 持续黑屏判定与车机端同源（audit WIN-07）。放在喂帧路径上：这里本来就在
            // 逐帧解析 NAL 头，顺带一次整数比较，比另起线程便宜。
            blackScreen.onFrame(isKeyFrame, frame.payload.size)
        }
        // 注意：硬解回退检查**不**在这里做（audit D-M2）。原来它是本方法的最后一步，
        // 而上面的 `gate ?: return` 让它在"解码器卡在 start() / 手机停帧"时永远跑
        // 不到 —— 硬解挂了就一直挂着。现在由独立的定时线程驱动，见 [fallbackLoop]。
    }

    /**
     * 硬解回退检查。只由 [fallbackTimer] 线程调用（audit D-M2）。
     *
     * **为什么从 feed 挪走**：原来它是 feed 的最后一步，而 feed 开头有
     * `gate ?: return` —— 解码器卡在 `grabber.start()`（管道还没建立）或手机
     * 干脆停帧（没有帧进来）时检查跑不到，硬解挂了就永远挂着，只剩黑屏。
     * 独立的定时线程不依赖"有没有帧喂进来"。
     *
     * 触发后关掉**当前**管道——解码线程阻塞在 `grab()` 上的读到 EOF 就会退出并重建，
     * 而 [hardwareInUse] 已是 false，重建出来的就是软解解码器。
     *
     * internal：单测要直接驱动它来验证判据（时间由注入的 [HardwareDecodeWatchdog]
     * 时钟决定），生产代码只从 [fallbackLoop] 调。
     */
    internal fun checkHardwareFallback() {
        if (!hardwareInUse) return
        if (!hardwareWatchdog.onFrame(framesDecoded.get())) return
        fallbackToSoftware("硬件解码 ${HardwareDecodeWatchdog.DEFAULT_TIMEOUT_MS}ms 内无任何输出 —— 回退软解")
        // D-M2：关的是"这一刻的当前管道"。管道引用在这里现取现用（同一行内），
        // 不存在跨语句被解码线程替换的窗口。
        runCatching { pipe?.close() }
    }

    /**
     * 硬解回退的落地动作（audit R3-SRP-17）：置标志 + 记日志只有这一个点。
     *
     * 两条触发路径各有自己的后续——[checkHardwareFallback] 关管道唤醒阻塞的读、
     * 解码线程侧交给外层重建——所以调用方保留各自的收尾动作，这里只统一状态翻转。
     */
    private fun fallbackToSoftware(reason: String) {
        hardwareInUse = false
        log(reason)
    }

    /**
     * 停止管线：关闭管道唤醒阻塞中的 FFmpeg 读，然后等解码线程退出。
     *
     * ── join 超时怎么办（audit D-M1）──
     * `interrupt()` 打不断 native `av_read_frame`（FFmpeg 在 JNI 里等数据），
     * 所以 2s 的 join 完全可能超时。此时线程**必须**被当作已抛弃：它手上的 native
     * FFmpeg / D3D11VA 上下文与 FFmpegFrameGrabber 都不会再释放（stop/release 都在
     * 线程自己的 finally 里）。处理方式是：
     *  1. 大声计数（[abandonedDecoders]）并打日志 —— 泄漏不再无声无息；
     *  2. 反复 close 管道：`VideoStreamPipe` 的 `ensureCurrent` 每 100ms 查一次
     *     `closed`，多关几轮能让阻塞在读上的线程收到 EOF 而走到自己的 finally；
     *  3. 若仍在跑，[onDecoderAbandoned] 向上升级。
     * 会话中途 restart 每次泄漏一线程 + 一 native 上下文，这是它的可见化路径。
     */
    fun stop() {
        if (!running) return
        running = false
        liveness.reset()
        gate = null
        pipe?.close()
        decodeThread?.interrupt()
        decodeThread?.join(STOP_JOIN_TIMEOUT_MS)
        fallbackTimer?.interrupt()
        if (decodeThread?.isAlive == true) {
            abandonedDecoders.incrementAndGet()
            log(
                "stop() 超时：解码线程未在 ${STOP_JOIN_TIMEOUT_MS}ms 内退出（累计抛弃 " +
                    "${abandonedDecoders.get()} 个）—— 该线程的 native 解码器无法回收",
            )
            // 解阻塞：ensureCurrent 每 100ms 查一次 closed，多关几轮提高 EOF 到达率
            repeat(UNBLOCK_CLOSE_ATTEMPTS) {
                if (decodeThread?.isAlive != true) return@repeat
                runCatching { pipe?.close() }
                runCatching { decodeThread?.join(UNBLOCK_JOIN_MS) }
            }
            if (decodeThread?.isAlive == true) {
                onDecoderAbandoned?.invoke()
            }
        }
        decodeThread = null
        fallbackTimer = null
    }

    private fun decodeLoop() {
        val converter = Java2DFrameConverter()
        while (running) {
            val currentPipe = VideoStreamPipe()
            val currentGate = FeedGate(currentPipe::write, config)
            pipe = currentPipe
            gate = currentGate
            currentGate.beginStream()
            // 本次重建是否用硬解。回退之后再重建就走软解（hardwareInUse 已被置 false）。
            val useHardware = hardwareInUse
            if (useHardware) hardwareWatchdog.onStreamStarted()

            var grabber: FFmpegFrameGrabber? = null
            try {
                // 必须用双参构造（maximumSize=0）：单参构造会传 Integer.MAX_VALUE-8，从而
                // 启用 JavaCV 的 seek 模拟——FFmpeg 在 avformat_find_stream_info 中询问
                // AVSEEK_SIZE 时，它靠"skip 整个流直到 EOF"来测量长度；直播流永远没有 EOF，
                // start() 会永久阻塞并把喂入的数据全部吞掉（真机第 1 轮踩坑）。
                // maximumSize=0 表示禁用 seek（JavaCV 源码注释：disable seek and minimize startup time）。
                grabber = FFmpegFrameGrabber(currentPipe.inputStream, 0).apply {
                    setFormat("h264")                        // 原始 Annex B 码流模式
                    setPixelFormat(avutil.AV_PIX_FMT_BGR24)  // 直接出 BGR24，便于转 BufferedImage
                    // 直播流不需要默认 5s 的流信息探测，压小以免首帧延迟过大
                    setOption("analyzeduration", "1000000")
                    setOption("probesize", "500000")
                    if (useHardware) {
                        // 不设 hwaccel_output_format：让 FFmpeg 自行决定，需要时自动
                        // hwdownload 回系统内存（我们要的是 AV_PIX_FMT_BGR24）。
                        setVideoOption("hwaccel", hwaccel)
                    }
                }
                try {
                    grabber.start()
                } catch (e: Exception) {
                    // 硬解初始化失败：本次运行内不再试，交给外层重建走软解
                    if (useHardware) {
                        fallbackToSoftware("硬件解码启动失败（${e.message}）—— 回退软解")
                    }
                    throw e
                }
                log(
                    "decoder started: ${grabber.imageWidth}x${grabber.imageHeight} " +
                        "codec=${grabber.videoCodecName} hwaccel=${if (useHardware) hwaccel else "none"}"
                )

                while (running) {
                    val frame = grabber.grab()
                    if (frame == null) {
                        // 读到 EOF（管道被关闭）或流解析结束；会话仍在则走重建
                        log("grab() 返回 null：码流结束或输入被关闭")
                        break
                    }
                    if (frame.image == null) continue
                    framesDecoded.incrementAndGet()
                    onImage?.invoke(converter.getBufferedImage(frame))
                }
            } catch (e: Exception) {
                if (running) log("decode error: ${e.message}")
            } finally {
                gate = null
                pipe = null
                runCatching { grabber?.stop() }
                runCatching { grabber?.release() }
                currentPipe.close()
            }

            if (running) {
                decoderRebuilds.incrementAndGet()
                val g = currentGate
                log(
                    "decoder rebuild #${decoderRebuilds.get()}：重放 CONFIG 并等下一个 IDR " +
                        "(droppedPreConfig=${g.droppedPreConfig} skippedP=${g.skippedPFrames})"
                )
                // 指数退避（audit D-M3）：持续失败时固定 500ms 的紧凑重试会以
                // ~2 次/秒无限转下去（坏 CONFIG 重放让 grab() 立刻抛），既烧 CPU
                // 又没有任何症状变化。从第 [BACKOFF_ESCALATE_AFTER] 次起退避加倍，
                // 封顶 [REBUILD_BACKOFF_MAX_MS]；到 [MAX_REBUILDS] 向上升级。
                val backoff = rebuildBackoffMs(decoderRebuilds.get())
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    // stop() 打断休眠：回到循环顶部退出
                }
                if (decoderRebuilds.get() >= MAX_REBUILDS) {
                    // D-M3：码流反复重建仍出不了画面 —— 这一代已经没救了。
                    // 只升级一次（[escalated] 闩锁）：isAlive 的循环继续以 8s 一次的
                    // 重建兜底自愈，把"怎么办"的决定权交给上层（桌面端触发自动重连）。
                    if (escalated.compareAndSet(false, true)) {
                        log("解码器连续重建 ${MAX_REBUILDS} 次仍无画面 —— 向上升级")
                        onDecodeStalled?.invoke()
                    }
                }
            }
        }
        log("decode thread 退出：decoded=${framesDecoded.get()} rebuilds=${decoderRebuilds.get()}")
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    /**
     * 第 [rebuilds] 次重建前要睡多久（audit D-M3，统一引用 [BackoffPolicy.TOLERANT]）。
     *
     * 前 [BACKOFF_ESCALATE_AFTER] 次保持 [REBUILD_BACKOFF_MS] 的紧凑恢复
     * （正常的单次码流抖动值得快速爬起来），之后每次翻倍直到
     * [REBUILD_BACKOFF_MAX_MS] 封顶。抽成纯函数是为了能单测退避曲线 ——
     * 这条曲线的形状直接决定"持续失败时是否空转"。
     */
    internal fun rebuildBackoffMs(rebuilds: Long): Long = BackoffPolicy.TOLERANT.compute(rebuilds)

    companion object {
        /** Windows 上的 D3D11 硬解加速器名（FFmpeg 的 `-hwaccel` 取值）。 */
        const val HWACCEL_D3D11VA = "d3d11va"

        /**
         * 桌面端的持续黑屏窗口（audit WIN-07）。
         *
         * 比 protocol-core 的默认值（3s，车机端在用）长得多：桌面端命中后做的是
         * **重连**，而重连本身要中断画面数秒。用户把手机停在一个暗色界面、或视频
         * 暂停在全黑画面，同样会持续输出极小 I 帧 —— 长窗口让这类合法场景基本不命中。
         */
        const val BLACK_SCREEN_SUSTAIN_MS = 10_000L

        /**
         * 「解码还活着」的判定窗口：最近这么久内 [framesDecoded] 没增长就算停摆。
         *
         * 2s 的取值：正常播放 24~30fps，2s 里至少该解出 48 帧；2s 一帧都没有 =
         * 解码线程确实卡住了（不是帧率抖动）。比黑屏窗口（10s）短得多，所以
         * 「内容黑但正常解码」永远进不了升级分支。
         */
        private const val DECODE_ALIVE_WINDOW_MS = DecodeLivenessTracker.DEFAULT_WINDOW_MS

        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
        private const val REBUILD_BACKOFF_MS = BackoffPolicy.DEFAULT_BASE_MS

        /** 从第几次重建开始加倍退避（audit D-M3，对应 [BackoffPolicy.TOLERANT]）。 */
        private const val BACKOFF_ESCALATE_AFTER = 3L

        /** 退避上限（audit D-M3）：8s —— 足够让编码器/网络缓过来，又不至于让用户等太久。 */
        private const val REBUILD_BACKOFF_MAX_MS = BackoffPolicy.DEFAULT_MAX_MS

        /** 500ms 翻几次到 8s：500→1000→2000→4000→8000，共 4 次。 */
        private const val MAX_BACKOFF_DOUBLINGS = BackoffPolicy.DEFAULT_MAX_DOUBLINGS

        /** 连续重建到这个次数就向上升级（audit D-M3）。 */
        private const val MAX_REBUILDS = 30L

        /** 硬解看门狗的轮询间隔（audit D-M2）。 */
        private const val HARDWARE_POLL_MS = 100L

        /** 解阻塞被抛弃的解码线程时，重复 close 管道的次数（audit D-M1）。 */
        private const val UNBLOCK_CLOSE_ATTEMPTS = 5

        /** 每轮解阻塞 close 后等解码线程退出的时间（audit D-M1）。 */
        private const val UNBLOCK_JOIN_MS = 200L
    }
}