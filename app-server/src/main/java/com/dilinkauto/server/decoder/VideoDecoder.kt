package com.dilinkauto.server.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock

import android.util.Log
import android.view.Surface
import com.dilinkauto.protocol.BlackScreenDetector
import com.dilinkauto.protocol.ContentBlackHealTracker
import com.dilinkauto.protocol.H264NalParser
import com.dilinkauto.protocol.VideoConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes H.264 video frames received from the phone and renders them to a Surface.
 *
 * Frames are queued from the network thread and fed to MediaCodec on a dedicated thread.
 * CONFIG frames (SPS/PPS) are cached so the decoder can be started/restarted at any time.
 */
class VideoDecoder {

    /** Log callback — set by CarConnectionService to route logs to the phone via protocol. */
    var logSink: ((String) -> Unit)? = null

    /**
     * When true, the per-30-frame stat log includes cumulative decode time and
     * queue depth. Default off — the volume is noise in release. Toggled by
     * CarConnectionService when dev diagnostics are enabled (Phase L1 / perf 9.3).
     */
    @Volatile
    var debugFrameStats = false

    /**
     * 实测入帧帧率（issue #203），**始终开启**：每喂满 30 帧按墙钟差折算一次。
     *
     * 只做统计，不参与任何调度判定。设置页用它显示「设了 30 fps 实际拿到多少」，
     * 与手机端 `Pipeline:` 行的 `newFps=` 相互印证 —— 两端都不再加协议字段。
     * 0 = 本会话还没有满一个 30 帧窗口。
     */
    @Volatile
    var measuredFps: Double = 0.0
        private set

    /** 实测帧率窗口满 30 帧后回调一次（喂帧线程），用于把值推给 UI 层。 */
    var onMeasuredFps: ((Double) -> Unit)? = null

    /** 上一个实测窗口的起点（`elapsedRealtime`）；0 = 还没开始计时。 */
    private var fpsWindowStartMs = 0L

    // logSink routes through carLogSend, which already calls Log.i (single logcat path).
    // Avoid double-logging here — the carLogSend side is the one source of truth for logcat.
    private fun log(msg: String) {
        logSink?.invoke("[VideoDecoder] $msg") ?: Log.i(TAG, msg)
    }

    private fun logW(msg: String) {
        logSink?.invoke("[VideoDecoder][W] $msg") ?: Log.w(TAG, msg)
    }

    private fun logE(msg: String) {
        logSink?.invoke("[VideoDecoder][E] $msg") ?: Log.e(TAG, msg)
    }

    /**
     * 必须绕过 `CarLogWriter.enabled` 的诊断行出口（issue #200，接线方式同 #191/#197 的
     * `AudioPlayer.diagSink`）。
     *
     * `LINK GAP` / `STALLED` 正是实车回签时要 grep 的证据，而 release 包默认关闭普通日志；
     * 走 [diagSink] → `CarLogWriter.sendUnconditional()` 才能保证车上抓得到。
     */
    var diagSink: ((String) -> Unit)? = null

    /** 诊断行：优先走 [diagSink]（绕过 enabled），未接线时退回普通日志。 */
    private fun logDiagW(msg: String) {
        diagSink?.invoke("[VideoDecoder][W] $msg") ?: logW(msg)
    }

    internal var codec: MediaCodec? = null
    private var feedThread: Thread? = null
    internal val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()
    // 容量见 FRAME_QUEUE_CAPACITY（issue #163：与 FeedBurst.MAX_FRAMES_PER_ROUND 对齐）。
    private val frameQueue = ArrayBlockingQueue<FrameData>(FRAME_QUEUE_CAPACITY)

    /**
     * Guards start()/stop() as whole critical sections (audit S-07/S-08).
     *
     * MediaCodec lifecycle is cross-thread: MirrorScreen's surfaceCreated
     * (Main, now via the service's decoder executor) creates the codec while
     * handleDisconnect/shutdown tear it down from other threads. Without this
     * lock, `running.getAndSet(true)` before codec creation lets a concurrent
     * stop() release a codec nobody is feeding, or flip `running` false so the
     * fresh feed thread exits immediately — the orphan-codec / black-screen
     * pair. start()/stop() bodies run entirely inside this lock.
     */
    private val lifecycleLock = Any()

    /**
     * Guards concurrent access to [codec] between [switchSurface] and the feed/drain operations
     * on [feedThread] (issue #117). MediaCodec is not thread-safe; calling setOutputSurface
     * concurrently with dequeue/release buffers triggers Native SIGSEGV in libstagefright.
     */
    internal val codecLock = Any()

    // Surface validity flag. SurfaceView (used since the Adreno 505 TextureView
    // composite cost was too high) destroys its surface when the view goes INVISIBLE;
    // TextureView kept it alive. Navigation HOME<->APP toggles visibility, so
    // surfaceDestroyed fires on each switch. The decoder keeps running across
    // navigation (handleDisconnect/shutdown owns the final stop); we gate
    // releaseOutputBuffer's render flag on this so we don't render to a destroyed
    // surface during the brief window before surfaceCreated re-attaches.
    @Volatile
    internal var outputSurfaceValid = false

    /**
     * 当前绑定的 Surface 上**真正上屏**过的帧数（issue #198）。
     *
     * 只在 `releaseOutputBuffer(..., render = true)` 时自增 —— 与 [renderCount] 不同，
     * 后者在渲染闸门关闭（Surface 已销毁）时也会自增，因此不能当作「画面可见」的证据。
     * `start` / `switchSurface` / `invalidateSurface` 归零，状态机据此判断能否报 STREAMING。
     */
    @Volatile
    internal var framesRenderedOnSurface = 0L

    // CONFIG data is cached separately so it's never lost, even if it arrives
    // before start() is called or while the queue is full.
    @Volatile
    private var configData: ByteArray? = null
    /**
     * 停播期缓存的 IDR（带 codec 代次，issue #216）。
     *
     * 以前只有裸字节，换代后的新 codec 会把它当作冷启动参考帧喂进去，把 `awaitingIdr`
     * 骗过去；现在代次不同的缓存一律作废，冷启动只喂 CONFIG 并主动请求一个新 IDR。
     * 读-清-记账都收在 [CachedKeyFrameStore] 的锁里，CONFIG 与喂帧线程并发清缓存时
     * 不会双重销账（issue #205 遗留竞态）。
     */
    private val cachedKeyFrameStore = CachedKeyFrameStore()

    /**
     * 参考链断裂后的重同步状态机（issue #9）。
     *
     * 任何一次 P 帧丢弃（队列满、为关键帧驱逐、输入缓冲超时）都会让解码器的参考链
     * 出现洞；旧实现只在连续 10 次输入失败后 flush，普通丢弃继续喂 P 帧 → 马赛克，
     * 直到下一个自然 IDR。现在统一走 [ResyncPolicy]：丢弃即停喂 P 帧，等到 IDR 再恢复。
     */
    internal val resync = ResyncPolicy()

    /** 渲染停顿诊断（issue #58）：喂帧线程每轮 tick 一次，停顿超阈值时打一行 STALL 汇总。 */
    private val renderStall = RenderStallDetector()

    /**
     * 停顿分级（issue #200）：链路静默 1.5s 先打 `LINK GAP` 并预置重同步，3s 升级 `STALLED`。
     *
     * [RenderStallDetector] 只在**渲染推进**时出报告，所以现场那 5983ms 空档直到恢复才被
     * 看见；分级策略按「时间流逝」观察，让停顿在发生时就可见、可动作。
     */
    private val stallEscalation = StallEscalationPolicy()

    /**
     * 距离最近一次收到控制帧的毫秒数（issue #200）。由 `CarConnectionService` 注入；
     * 未接线时返回 -1（日志打 `n/a`）。
     *
     * GAP / STALLED 行带上它就能区分两种完全不同的故障：链路断时控制帧一起停，
     * 编码停（手机端不产帧）时控制帧照常。
     */
    @Volatile
    var msSinceControlFrame: (() -> Long)? = null

    /**
     * 收帧 / 喂帧 / 丢弃的唯一账本（issue #205）。
     *
     * 以前 `receiveCount / frameCount / dropCount / keyFrames*` 是五个各自 `++` 的散字段：
     * 冷启动补喂缓存 IDR 没记 fed、`stop()` 清队列与缓存不记账、`!configFed` 早退
     * 直接 `return` —— 现场 `keys_recv=123 keys_fed=118 keys_drop=0` 的「差 5」就是这么来的。
     * 现在四个数是同一台账的四项，不变式 `received == fed + dropped + pending` 每行统计都校验。
     * `internal` 供行为级单测直接判决账目。
     */
    internal val intake = IntakeAccounting()

    /**
     * MediaCodec 生命周期台账（issue #205）：创建/释放各打一行 `codec generation=N`。
     *
     * 现场「建 5 次只停 4 次」的真相是断线瞬间的 `Stopping decoder` 进了离线缓冲、被下个
     * 会话 `beginSession` 清掉，但日志里没有配对锚点时「泄漏」和「日志丢了」无法区分。
     */
    private val codecLedger = CodecLifecycleLedger()

    /** 当前 codec 的代号（0 = 没有活着的 codec）；汇总行与 finally 释放都用它配对。 */
    @Volatile
    private var codecGeneration = 0

    /**
     * 累计出帧数。喂帧线程在 drainOutput() 里自增，网络线程的黑屏闸门会读它
     * （issue #11），所以必须 @Volatile。只用于「是否还在增长」的比较，不需要原子自增。
     */
    @Volatile
    private var renderCount = 0L
    private var inputFailCount = 0L

    /**
     * 重同步期间在**接收入口**被丢掉的 P 帧数（issue #200），与总账里的 `drops` 分开计数。
     *
     * 总账（[intake]）的 `drops` 保留「参考链因积压/超时产生的洞」的原口径；这里的帧根本
     * 没进队列，不会占满 8 槽、也不会挤掉关键帧。`internal` 供行为级单测观察。
     */
    internal var resyncSkippedFrames = 0L

    /**
     * 当前接收队列里的帧数（只读）。仅用于行为级单测断言「重同步期的 P 帧没有入队」，
     * 生产代码不应据此做控制流判断。
     */
    internal val queuedFrames: Int get() = frameQueue.size

    /**
     * 黑屏自愈的升级闸门（issue #11）。
     *
     * 纯码流判据分不清「内容本身是黑」（切 app 后的启动/加载页纯色）与「管线卡死」。
     * 接上 [DecodeLiveness] 后：黑屏满窗时若解码仍在出帧 = 内容黑，不升级；只有出帧
     * 停摆才真正重握手。桌面端同款语义见 `VideoDecodePipeline.decodingAlive()`。
     */
    private val liveness = DecodeLiveness()

    /**
     * 「解码存活但内容一直黑」的自愈节奏（issue #15）。
     *
     * #11 的闸门在"内容黑 + 解码存活"时只抑制重建 —— 对纯色启动页是对的，但实车
     * 出现过 VD 顶层没有可见窗口、黑屏可持续数十秒且不会自恢复的情况。这里在闸门
     * 判定"存活"之后追加一级：黑满 [ContentBlackHealTracker.DEFAULT_FIRST_REQUEST_AFTER_MS]
     * 就向上游请求一次轻量自愈，之后限流重试，而不是等用户断连重连。
     */
    private val contentBlackHeal = ContentBlackHealTracker()

    /**
     * 闸门抑制升级的日志限流时刻（issue #11）：同一段内容黑里只留少量痕迹，
     * 便于实车回签确认「确实是被闸门挡住」而不是解码停摆。
     */
    private var lastGateLogMs = 0L

    // 黑屏检测策略在 protocol-core（车机端 VideoDecoder 与桌面端 VideoDecodePipeline
    // 共用一套判据，可脱离 MediaCodec 单测）。车机端沿用 elapsedRealtime（含休眠），
    // 桌面端用默认的 System.nanoTime。
    private val blackScreen = BlackScreenDetector(
        clock = { SystemClock.elapsedRealtime() },
    ).apply {
        sustainMs = BLACK_SCREEN_SUSTAIN_MS
        decodingAliveCheck = {
            val now = SystemClock.elapsedRealtime()
            val alive = liveness.isAlive(renderCount, now)
            if (alive) {
                logSuppressedRebuild()
                // 存活但持续黑：交给 tracker 决定是否向上游要一次显示自愈（issue #15）。
                if (contentBlackHeal.onAliveBlackFrame(now)) onContentBlackPersistent?.invoke()
            } else {
                // 解码停摆是真故障，走重握手；自愈节奏同时清空，避免恢复后立刻再发。
                contentBlackHeal.reset()
            }
            alive
        }
    }

    /**
     * Called once per session when the stream is *persistently* black — not on
     * the third tiny keyframe, but only after a sustained window. See
     * [BlackScreenDetector] for the rationale.
     */
    @Volatile
    var onSustainedBlackScreen: (() -> Unit)? = null

    /**
     * 参考链首次断裂时回调（issue #9），仅在同一次缺口里回调一次。
     *
     * 车机端据此经 CONTROL 通道向手机索要一个 IDR（[com.dilinkauto.protocol.ControlMsg.REQUEST_SYNC_FRAME]），
     * 把重同步等待从「下一个自然 GOP 边界（最多 ~1.2s）」缩短到一次往返。
     * 可能从网络线程或喂帧线程触发，实现方必须自行处理线程安全与限流。
     */
    @Volatile
    var onNeedsSyncFrame: (() -> Unit)? = null

    /**
     * "解码存活且码流持续是黑"时的轻量自愈回调（issue #15）。
     *
     * 触发频率由 [ContentBlackHealTracker] 限流，实现方直接发一条
     * [com.dilinkauto.protocol.ControlMsg.REQUEST_DISPLAY_HEAL] 即可，不必再做节流。
     * 从网络线程调用，必须立即返回。
     */
    @Volatile
    var onContentBlackPersistent: (() -> Unit)? = null

    /**
     * 当前 Surface 上**第一帧真正上屏**时回调（issue #198 / #200）。
     *
     * 从喂帧线程、且持有 [codecLock] 的路径里触发，实现方只能做轻量投递
     * （例如切回自己的协程），绝不能在回调里做阻塞 IO 或状态机工作。
     *
     * 存在的意义：就绪判定在「收帧」路径上采样，一旦首帧渲染后码流停住（#200 的 6 秒停滞），
     * 就再也没有下一次收帧来采样，UI 会一直停在「等待视频」。这条回调把「已渲染过帧」
     * 这个事实直接送出去，不依赖后续帧。
     */
    @Volatile
    var onFirstFrameOnSurface: (() -> Unit)? = null

    /** Reset the black-screen detector so a fresh session starts clean. */
    fun resetBlackScreenState() = blackScreen.reset()

    /**
     * 黑屏满窗但解码仍在出帧 —— 内容黑，不重握手（issue #11）。
     *
     * 只在闸门问询时调用（网络线程、每帧一次），用 [ALIVE_GATE_LOG_INTERVAL_MS] 限流。
     */
    private fun logSuppressedRebuild() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGateLogMs < ALIVE_GATE_LOG_INTERVAL_MS) return
        lastGateLogMs = now
        log("[BLACK] decode still alive (rendered=$renderCount) — content is black, VD rebuild suppressed")
    }

    /**
     * 记录一次参考链缺口；只有首次进入重同步时才向上游索要 IDR。
     *
     * 缺口点分布在网络线程（队列丢弃）与喂帧线程（输入缓冲失败），[resync] 内部互斥。
     *
     * @return 本次调用是否真的发出了 IDR 请求（首次缺口）。冷启动依赖这个返回值区分
     *   「我请求了」与「已经在等 IDR」，避免重复占用 `SyncRequestGate` 名额。
     */
    internal fun noteGap(): Boolean {
        if (!resync.onGap()) return false
        logW("Reference chain broken — waiting for next IDR (${intake.snapshot().dropped} drops, ${inputFailCount} inputFails)")
        onNeedsSyncFrame?.invoke()
        return true
    }

    /**
     * GAP / STALLED 行里的控制通道静默时长文本（issue #200）。
     *
     * `n/a` = 没有控制连接或未接线，不代表链路正常；有数字且远大于心跳间隔（3s）
     * 才说明是链路断而不是编码停。
     */
    private fun controlSilenceText(): String {
        val ms = msSinceControlFrame?.invoke() ?: -1L
        return if (ms >= 0) "${ms}ms" else "n/a"
    }

    // Accumulated decode time for the current 30-frame window (Phase L1 / perf 9.3).
    // Reset at each per-30-frame log so the reported value is per-window, not lifetime.
    private var windowDecodeNanos = 0L

    /** True if [data] contains an H.264 IDR frame (NAL type 5). Delegates to
     *  [H264NalParser]（已迁至 :protocol-core，桌面端共用同一判定）；薄包装让调用点保持本地命名。 */
    private fun isKeyFrame(data: ByteArray): Boolean = H264NalParser.isKeyFrame(data)

    data class FrameData(val isConfig: Boolean, val isKeyFrame: Boolean, val data: ByteArray)

    /**
     * Starts the decoder, rendering to the provided Surface.
     *
     * Never call this from the main thread in production — the service routes
     * it through its decoder executor (audit S-M3). The whole body is
     * serialized against [stop] by [lifecycleLock] (audit S-07/S-08).
     *
     * @param requestIdrOnColdStart 冷启动没有可复用参考帧时是否在这里主动请求一次 IDR
     *   （issue #216）。默认 `true` 覆盖「重连 / Surface 冷启动」；[recreateDecoder] 传
     *   `false`，因为它随后会用 `requestSyncAfterRebuild()` 走 #207 的**唯一**请求点，
     *   否则一次重建会在 `SyncRequestGate`（窗口内允许 2 次）里真的发出两次。
     */
    fun start(
        surface: Surface,
        width: Int,
        height: Int,
        fps: Int = VideoConfig.TARGET_FPS,
        requestIdrOnColdStart: Boolean = true,
    ) {
        synchronized(lifecycleLock) {
            if (running.getAndSet(true)) {
                logW("start() called but already running")
                return
            }

            // fps can be 0 or absurd from a persisted pref (audit S-L2): the
            // frameQueue.poll timeout below divides by it, and MediaFormat's
            // operating rate must be a real value. Coerce into the protocol's
            // declared range once, here, so every downstream use is safe.
            val effectiveFps = coerceFps(fps)
            if (effectiveFps != fps) {
                logW("fps $fps out of range — coerced to $effectiveFps")
            }

            log("Starting decoder: ${width}x${height} @${effectiveFps}fps, cached config=${configData != null}, queued=${frameQueue.size}")

            // Re-arm the black-screen detector: a fresh stream gets a fresh chance, and
            // the "escalate once" latch must not survive into the new session.
            resetBlackScreenState()
            // 新会话的出帧计数从 0 起算，闸门基线一并复位（issue #11）。
            liveness.reset()
            // 自愈节奏同样重新起算（issue #15）。
            contentBlackHeal.reset()
            // 新编解码器没有旧参考链的包袱，重同步状态同样重新起算（issue #9）。
            resync.reset()
            // 停顿诊断同样重新起算，避免把上一个会话的空档算进这一次（issue #58）。
            renderStall.reset()
            // 停顿分级同样重新起算（issue #200）。
            stallEscalation.reset()
            resyncSkippedFrames = 0L

            // Re-check `running` AFTER creating the codec (audit S-08): a
            // concurrent stop() that interleaved before the lock was taken
            // (or a cancelled executor task) must not leave a live codec nobody
            // feeds. Release it right here instead.
            val newCodec: MediaCodec = try {
                // issue #205：格式构造也可能抛（非法尺寸 → IAE），和 configure/start 一起纳入
                // 失败回退，别让调用线程（解码器执行器 / 重建入口）被异常打断。
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, effectiveFps)
                }
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, surface, null, 0)
                    start()
                }
            } catch (e: Exception) {
                logE("Failed to create/start MediaCodec: ${e.message}")
                running.set(false)
                return
            }
            codec = newCodec
            // issue #205：先领代号再判竞态 —— 这里的竞态回退与喂帧线程的 finally 是两条
            // 释放路径，共用同一个 generation，日志才能严格配对。
            val generation = codecLedger.onCreated()
            codecGeneration = generation

            if (!running.get()) {
                // Lost a race with stop(): tear down what we just built.
                logW("start(): stop() raced codec creation — releasing the fresh codec (codec generation=$generation)")
                releaseCodec(newCodec, generation, "start/stop race")
                codec = null
                return
            }

            outputSurfaceValid = true
            // 新解码器实例还没有在它绑定的 Surface 上出过帧（issue #198）。
            framesRenderedOnSurface = 0
            log("MediaCodec created: codec generation=$generation name=${newCodec.name} dims=${width}x${height} operatingRate=$effectiveFps")

            renderCount = 0
            inputFailCount = 0
            intake.reset()
            // issue #205：会话开始前队列里可能已经有帧（网络线程在 start() 之前入队），它们
            // 属于本会话的「已接收、待喂」，必须补记 recv + pending，否则后面喂入时 pending
            // 会变成负数。CONFIG 不进账（与 onFrameReceived 同口径）。
            frameQueue.forEach { queued -> if (!queued.isConfig) intake.onReceived(queued.isKeyFrame) }
            // issue #205：停播期缓存下来的 IDR 对本会话而言是「已接收、待补喂」——先播种，
            // 冷启动补喂时再由 onFed 销账。否则 fed 会凭空多出一帧，收/喂/丢三本账永远差 1
            // （现场 5 次会话差 5 正是这么来的）。
            if (cachedKeyFrameStore.hasEntry()) intake.onReceived(isKey = true)
            // issue #203：新解码器实例重新起算实测帧率窗口。
            fpsWindowStartMs = 0L
            measuredFps = 0.0
            var configFed = false

            feedThread = Thread({
                // 8x A53 has no big cores; tell the scheduler this is latency-critical
                // so it doesn't get starved by background work (icon decode, log flush).
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                // Capture the codec reference the thread owns (audit S-07): if
                // stop() cannot join us, the thread's own finally releases
                // *this* instance — never the field, which may already point at
                // a newer one.
                val decoder: MediaCodec = newCodec
                try {
                    log("Feed thread started")

                    // If we have cached config, feed it first before any frames.
                    // Without SPS/PPS, the decoder silently fails on non-config frames.
                    configData?.let { config ->
                        log("Feeding cached CONFIG (${config.size} bytes)")
                        feedBuffer(decoder, config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        configFed = true
                    }

                    // issue #216：缓存 IDR 只有在**同一个 codec 实例**（代次相同）时才能当参考帧。
                    // 换代后旧帧对应的编码器会话已经不存在，喂进去只会把 awaitingIdr 骗过去，
                    // 随后到来的 P 帧全部解成空帧/花屏，直到新 IDR 到达（现场约 9s）。
                    //
                    // 说明：缓存只在 !running 时写入，而 start() 必然领取新 codec 代号，因此
                    // 当前调用路径下 Reusable 分支实际不可达；保留它是「不换 codec、只重绑
                    // Surface」的防御性语义，并让「跨代拒绝」成为可单测的生产代码
                    // （CachedKeyFrameStore + CachedKeyFrameGenerationTest）。
                    val reusedCachedKeyFrame = when (val cached = cachedKeyFrameStore.take(generation)) {
                        is CachedKeyFrameStore.TakeResult.Reusable -> {
                            log(
                                "Feeding cached KEYFRAME for cold-start reference " +
                                    "(${cached.entry.data.size} bytes, codec generation=${cached.entry.generation})"
                            )
                            // issue #205：补喂的是「已接收」的帧（start() 里播种），成功记 fed、
                            // 失败记 drop。以前这里既不记 fed 也不记 drop，正是差 5 的来源。
                            if (feedBuffer(decoder, cached.entry.data, 0)) {
                                intake.onFed(isKey = true)
                                true
                            } else {
                                intake.onDropped(isKey = true)
                                false
                            }
                        }

                        is CachedKeyFrameStore.TakeResult.Discarded -> {
                            // 新 CONFIG 已经就位，但参考帧属于另一代 codec：直接作废并平账。
                            logW(
                                "Discarding cached KEYFRAME from codec generation=${cached.entry.generation} " +
                                    "(current generation=$generation) — waiting for a new IDR"
                            )
                            intake.onDropped(isKey = true)
                            false
                        }

                        CachedKeyFrameStore.TakeResult.Empty -> false
                    }

                    // 冷启动没有可用参考帧（跨代缓存 / 缓存喂入失败 / 本来就没有缓存）：
                    // P 帧在坏参考链上只会产空帧，统一交给 awaitingIdr 路径 —— 接收入口与
                    // 喂帧循环都会拒喂 P 帧，只放行新 IDR（issue #216）。
                    //
                    // 默认在这里就调 noteGap() 主动请求一次 IDR：如果只置 awaitingIdr，
                    // 1.5s 后 GAP 升级走的 noteGap() 会因 resync.onGap() 返回 false 而把请求
                    // 吞掉，整条会话一次 IDR 请求都不发（实车重连不走 recreateDecoder，手机
                    // 侧不会主动补帧）。请求仍经 onNeedsSyncFrame → SyncRequestGate 限流。
                    //
                    // 重建路径（requestIdrOnColdStart=false）只置重同步，由
                    // `requestSyncAfterRebuild()` 统一请求（#207）：一次重建只占一个名额。
                    if (!reusedCachedKeyFrame) {
                        if (requestIdrOnColdStart) {
                            if (noteGap()) {
                                log("requesting IDR (reason=cold start without a usable reference frame)")
                            }
                        } else {
                            resync.onGap()
                            logW("Cold start without a usable reference frame — waiting for rebuild IDR request")
                        }
                    }

                    // issue #55：原实现每 1000/fps 毫秒只取 1 帧，消费速率被硬限在 30fps，
                    // 4 槽接收队列因此没有任何突发余量；关键帧/积压帧一突发就丢帧，丢帧又触发
                    // 「等下一个 IDR」的重同步，画面冻结数秒。下面先把单帧处理抽成函数，
                    // 再按 FeedBurst 的上限一轮排空队列，用满解码器输入能力
                    //（实测 decodeMs≈21.5ms/30 帧，远快于 30fps 输入）。
                    var skipCount = 0L

                    fun feedOne(frame: FrameData) {
                        if (frame.isConfig) {
                            configData = frame.data
                            log("Feeding CONFIG (${frame.data.size} bytes)")
                            feedBuffer(decoder, frame.data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            configFed = true
                            return
                        }
                        if (!configFed) {
                            if (intake.snapshot().fed == 0L) log("Waiting for CONFIG before feeding video frames")
                            // 帧已经从队列取出，必须销账：既不喂也不记丢，账就永远差这一帧（issue #205）。
                            intake.onDropped(isKey = frame.isKeyFrame)
                            return
                        }
                        val isKey = frame.isKeyFrame
                        // 参考链有洞时（issue #9），MediaCodec 用坏参考解 P 帧只会产马赛克。
                        // 停喂 P 帧、只等 IDR：onFrameReceived 给关键帧队列优先，下一个 IDR
                        // 很快到队头；能索要时还会经 onNeedsSyncFrame 让手机立刻补一个。
                        if (!resync.shouldFeed(isKey)) {
                            skipCount++
                            // 拒喂的帧同样是「已接收、未喂入」，必须记丢（issue #205）。
                            intake.onDropped(isKey)
                            if (skipCount <= 5 || skipCount % 30 == 0L) {
                                logW("Resync: skipping P-frame #$skipCount until next IDR")
                            }
                            return
                        }
                        if (skipCount > 0) {
                            log("Resync: resynced at IDR keyframe after skipping $skipCount P-frames")
                            skipCount = 0L
                        }
                        val decodeStart = if (debugFrameStats) System.nanoTime() else 0L
                        val fed = feedBuffer(decoder, frame.data, 0)
                        if (debugFrameStats) windowDecodeNanos += System.nanoTime() - decodeStart
                        if (fed) {
                            intake.onFed(isKey)
                            if (isKey) {
                                // 关键帧日志只在真的喂进去之后打，避免「记了 fed 但其实没喂」。
                                val fedNow = intake.snapshot()
                                log("Feeding KEYFRAME #${fedNow.keyFed} size=${frame.data.size} (fed=${fedNow.fed} rendered=$renderCount)")
                            }
                        } else {
                            intake.onDropped(isKey)
                        }
                        val snap = intake.snapshot()
                        if (snap.fed > 0L && snap.fed % 30 == 0L) {
                            // issue #203：实测入帧帧率（墙钟口径，始终开启），与手机端
                            // `Pipeline:` 行的 newFps= 相互印证；不进协议、只进日志与设置页。
                            val nowMs = SystemClock.elapsedRealtime()
                            if (fpsWindowStartMs > 0L) {
                                val elapsedMs = nowMs - fpsWindowStartMs
                                if (elapsedMs > 0L) {
                                    measuredFps = 30_000.0 / elapsedMs
                                    onMeasuredFps?.invoke(measuredFps)
                                }
                            }
                            fpsWindowStartMs = nowMs
                            val fpsText = " fps=%.1f".format(measuredFps)
                            val counters = "rendered=$renderCount drops=${snap.dropped} inputFails=$inputFailCount " +
                                "keys_recv=${snap.keyReceived} keys_fed=${snap.keyFed} keys_drop=${snap.keyDropped}"
                            if (debugFrameStats) {
                                val decodeMs = windowDecodeNanos / 1_000_000.0
                                log("Fed ${snap.fed} $counters queue=${frameQueue.size} skips=$skipCount resync=${resync.awaitingIdr} decodeMs=${"%.1f".format(decodeMs)}$fpsText")
                                windowDecodeNanos = 0L
                            } else {
                                log("Fed ${snap.fed} $counters queue=${frameQueue.size} skips=$skipCount resync=${resync.awaitingIdr}$fpsText")
                            }
                            // issue #205：每行统计校验一次不变式，不平立刻可查（四项数值一起打）。
                            intake.violationText()?.let { logW("Intake accounting mismatch: $it") }
                        }
                    }

                    while (running.get()) {
                        // Drain output first to free decoder buffers before trying to feed
                        synchronized(codecLock) {
                            if (running.get()) {
                                drainOutput(decoder)
                            }
                        }
                        // issue #58：记录「两次渲染推进之间的间隔」，停顿超阈值时留一条 STALL
                        // 汇总行。卡顿是「停顿后恢复」，恢复必经这里，所以不会漏报。
                        val tickNowMs = SystemClock.elapsedRealtime()
                        // issue #205：四个诊断数取同一份快照，避免逐字段读跨越两次自增。
                        val tick = intake.snapshot()
                        renderStall.onTick(
                            nowMs = tickNowMs,
                            snap = RenderStallDetector.Snapshot(
                                renders = renderCount,
                                receive = tick.received,
                                fed = tick.fed,
                                drops = tick.dropped,
                                inputFails = inputFailCount,
                                keysDropped = tick.keyDropped,
                                queueSize = frameQueue.size,
                                awaitingIdr = resync.awaitingIdr,
                                skips = skipCount,
                            ),
                        )?.let { r ->
                            logW("STALL ${r.gapMs}ms renders=${r.fromRenders}->${r.toRenders} recv+=${r.receiveDelta} fed+=${r.fedDelta} drops+=${r.dropDelta} inputFails+=${r.inputFailDelta} keys_drop+=${r.keyDropDelta} queue=${r.queueSize} resync=${r.awaitingIdr} skips=${r.skips}")
                        }
                        // issue #200：停顿分级按「时间流逝」观察，不必等到恢复才可见。
                        // GAP 级先把重同步预置好（noteGap 只在首次缺口返回 true，IDR 请求
                        // 仍走 onNeedsSyncFrame → SyncRequestGate 限流，不绕过 #75/#163），
                        // 这样链路恢复后的积压洪峰一到就直接跳到 IDR，而不是先白喂一段。
                        stallEscalation.onTick(nowMs = tickNowMs, renders = renderCount)?.let { r ->
                            val ctrlSilent = controlSilenceText()
                            when (r.level) {
                                StallEscalationPolicy.Level.GAP -> {
                                    logDiagW("LINK GAP ${r.gapMs}ms — no render advance (renders=$renderCount recv=${tick.received} fed=${tick.fed} drops=${tick.dropped} queue=${frameQueue.size} ctrlSilent=$ctrlSilent) — pre-arming resync + one IDR request")
                                    noteGap()
                                }
                                StallEscalationPolicy.Level.STALLED ->
                                    logDiagW("STALLED ${r.gapMs}ms — still no render advance (renders=$renderCount recv=${tick.received} fed=${tick.fed} drops=${tick.dropped} inputFails=$inputFailCount queue=${frameQueue.size} ctrlSilent=$ctrlSilent resync=${resync.awaitingIdr})")
                            }
                        }

                        // 空闲时仍按帧间隔阻塞等待，避免忙等；一旦有帧就立刻进入排空循环。
                        var frame = frameQueue.poll()
                        if (frame == null) {
                            frame = try {
                                frameQueue.poll(1000L / effectiveFps, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                continue
                            } ?: continue
                        }

                        var fedThisRound = 0
                        while (frame != null) {
                            val curFrame = frame
                            synchronized(codecLock) {
                                if (running.get()) {
                                    feedOne(curFrame)
                                    // 每喂一帧就排空一次输出，及时归还解码器输入缓冲。
                                    drainOutput(decoder)
                                }
                            }
                            fedThisRound++
                            if (!FeedBurst.shouldContinue(fedThisRound, frameQueue.isNotEmpty())) break
                            frame = frameQueue.poll()
                        }
                    }
                    val exitSnap = intake.snapshot()
                    log("Feed thread exiting: fed=${exitSnap.fed} rendered=$renderCount drops=${exitSnap.dropped}")
                } finally {
                    // The thread owns the codec it captured (audit S-07): release
                    // it here whether we exited normally or threw. This is the
                    // only release path that is guaranteed to run *after* this
                    // thread has left every native MediaCodec call.
                    synchronized(codecLock) {
                        releaseCodec(decoder, generation, "feed thread exit")
                    }
                }
            }, "VideoDecoderFeed").apply { start() }
        }
    }

    internal var consecutiveDrops = 0
    private val drainBufferInfo = MediaCodec.BufferInfo()

    internal fun feedBuffer(decoder: MediaCodec, data: ByteArray, flags: Int): Boolean {
        if (!running.get()) return false
        try {
            // 2ms 太短：喂帧线程刚 drain 完输出，输入缓冲偶尔还在解码器手里，
            // 一次失败就丢整帧并打断参考链（issue #9）。10ms 只影响异常路径，
            // 正常路径 dequeueInputBuffer 立即返回。
            // 针对偶发调度抖动（issue #125）：允许最多 MAX_ATTEMPTS 次尝试（每次 10ms），并在重试前主动 drainOutput，
            // 避免单次 10ms 抖动误判参考链断裂导致后续 1~2 秒持续冻屏。
            var inputIndex = -1
            for (attempt in 0 until VideoDecoderStallPolicy.MAX_ATTEMPTS) {
                inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) break
                if (attempt < VideoDecoderStallPolicy.MAX_ATTEMPTS - 1) {
                    drainOutput(decoder)
                }
            }
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                inputBuffer.clear()
                inputBuffer.put(data)
                val pts = System.nanoTime() / 1000 // wall-clock microseconds
                decoder.queueInputBuffer(inputIndex, 0, data.size, pts, flags)
                consecutiveDrops = 0
                return true
            } else {
                consecutiveDrops++
                inputFailCount++
                // 仅在重试耗尽且连续失败达到阈值时，才确认丢帧打断参考链并进入重同步，
                // 避免偶发的抖动导致长时间冻屏。
                if (VideoDecoderStallPolicy.shouldTriggerGap(flags, consecutiveDrops)) {
                    noteGap()
                }
                if (consecutiveDrops <= 3 || consecutiveDrops % 10 == 0) {
                    logW("dequeueInputBuffer failed: consecutiveDrops=$consecutiveDrops, size=${data.size}, flags=$flags")
                }
                if (VideoDecoderStallPolicy.shouldFlush(consecutiveDrops)) {
                    logW("Decoder stuck ($consecutiveDrops drops), flushing")
                    decoder.flush()
                    consecutiveDrops = 0
                    // flush 后解码器没有参考帧；resync 已在首次缺口时置位，喂帧循环
                    // 会丢 P 帧直到下一个 IDR。
                    resync.onGap()
                    configData?.let { config ->
                        val idx = decoder.dequeueInputBuffer(50_000)
                        if (idx >= 0) {
                            val buf = decoder.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(config)
                            decoder.queueInputBuffer(idx, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            log("Re-fed CONFIG after flush")
                        } else {
                            logE("Cannot re-feed CONFIG after flush — dequeueInputBuffer failed")
                        }
                    }
                }
                return false
            }
        } catch (e: Exception) {
            logE("Error feeding buffer to decoder: ${e.message}")
            return false
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        if (!running.get()) return
        val bufferInfo = drainBufferInfo
        try {
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex >= 0) {
                    // 一次读闸门：render=false 时只解不上屏（Surface 已销毁）。
                    // 只有真正交给 Surface 的帧才算「可见输出」（issue #198）。
                    val render = outputSurfaceValid
                    decoder.releaseOutputBuffer(outputIndex, render) // render to surface only if valid
                    renderCount++
                    if (render) {
                        framesRenderedOnSurface++
                        if (framesRenderedOnSurface == 1L) {
                            log("First frame rendered on bound surface — output is visible")
                            // 在 codecLock 内，只允许轻量回调（实现方自己切线程）。
                            onFirstFrameOnSurface?.invoke()
                        }
                    }
                    if (renderCount <= 3 || renderCount % 30 == 0L) {
                        log("Rendered frame #$renderCount size=${bufferInfo.size} flags=${bufferInfo.flags}")
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Output format changed: ${decoder.outputFormat}")
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            logE("Error draining decoder output: ${e.message}")
        }
    }

    /**
     * Enqueues a received frame for decoding.
     * Called from the network thread — must not block.
     *
     * CONFIG frames are always cached, even if the decoder hasn't started yet.
     *
     * Drop strategy: never evict CONFIG or keyframes. When the queue is full:
     *  - P-frames: just drop the incoming frame (least harm)
     *  - Keyframes/CONFIG: evict the oldest P-frame from the queue to make room.
     *    Only drop a keyframe as absolute last resort (all queued frames are keyframes).
     */
    fun onFrameReceived(isConfig: Boolean, data: ByteArray) {
        val isKey = !isConfig && isKeyFrame(data)
        // issue #205：CONFIG 不进账（它不是视频帧，`Fed` 的帧率口径与 #203 都按视频帧算），
        // 其余每帧在这里入账一次，之后只允许 onFed / onDropped 销账一次。
        if (!isConfig) intake.onReceived(isKey)
        val receivedNow = intake.snapshot().received
        // 黑屏检测：连续多个 I 帧都极小视为疑似黑屏（去重告警一次），出现正常大小 I 帧则清除。
        val wasAlerted = blackScreen.isAlerted()
        blackScreen.onSustainedBlackScreen = { onSustainedBlackScreen?.invoke() }
        blackScreen.onFrame(isKey, data.size)
        blackScreen.onSustainedBlackScreen = null
        if (blackScreen.isAlerted() && !wasAlerted) {
            logW("Suspected BLACK SCREEN: ${blackScreen.streak()} consecutive tiny keyframes, latest=${data.size}B")
        } else if (wasAlerted && !blackScreen.isAlerted()) {
            log("Black screen alert cleared — keyframe=${data.size}B")
            // 画面回来了：自愈节奏重新起算，别让上一段黑屏的起点漏到下一段。
            contentBlackHeal.reset()
        }
        if (isConfig || isKey || receivedNow <= 3 || receivedNow % 60 == 0L) {
            log("onFrameReceived #$receivedNow isConfig=$isConfig isKey=$isKey size=${data.size} running=${running.get()} queue=${frameQueue.size}")
        }
        // Always cache CONFIG — needed to bootstrap the next decoder instance.
        // A fresh CONFIG establishes a new stream boundary, so any stale cached
        // keyframe from the previous stream must be discarded.
        if (isConfig) {
            configData = data
            // 被新码流边界作废的缓存 IDR 从此再也不会喂入，必须记丢（issue #205）。
            if (cachedKeyFrameStore.clear()) intake.onDropped(isKey = true)
        }
        // When stopped, cache the most recent IDR so start() can decide whether it is
        // usable as a reference frame. P-frames are dropped (no codec to decode them).
        if (!running.get() && !isConfig) {
            if (isKey) {
                // 旧缓存被新 IDR 覆盖：它既不会被喂也不会再被解，记丢并留一行痕迹（issue #205）。
                // 代次取当前 codecGeneration：停播期通常为 0（没有活着的 codec），
                // stop 卡死时可能是尚未配对的旧代次 —— 两种情况都会被新 codec 判为跨代。
                if (cachedKeyFrameStore.replace(codecGeneration, data) != null) {
                    intake.onDropped(isKey = true)
                    logW("Cached KEYFRAME replaced by a newer one before start() — counted as dropped")
                }
            } else {
                // 停播期到达的 P 帧没有解码器可解，接收即丢（以前只记 recv，账面上凭空多一帧）。
                intake.onDropped(isKey = false)
            }
            return
        }
        // issue #200：等待 IDR 期间到达的 P 帧对解码毫无用处（喂帧循环里的
        // ResyncPolicy.shouldFeed 也会跳过），但它们会占满 8 槽队列、把关键帧挤到
        // 待驱逐的边缘。现场链路恢复后 150ms 内涌入 ~117 帧，正是这批注定作废的
        // 积压造成了 88 次 drops 与第二次「参考链断裂」。在接收入口直接丢掉，
        // 只留关键帧/CONFIG 参与排队。
        if (!isConfig && !isKey && resync.awaitingIdr) {
            resyncSkippedFrames++
            // 入口丢弃同样要销账（issue #205）：`resyncSkippedFrames` 是独立口径，
            // 但它必须同时体现为总账里的一次丢弃，否则 recv 会永远领先 fed+drop。
            intake.onDropped(isKey = false)
            if (resyncSkippedFrames <= 5 || resyncSkippedFrames % 60 == 0L) {
                logW("Resync: dropped P-frame at receive #$resyncSkippedFrames until next IDR (receive=$receivedNow queue=${frameQueue.size})")
            }
            return
        }
        val frame = FrameData(isConfig, isKey, data)

        // Keyframe priority: if queue has frames, drop P-frames to make room
        if (isConfig || isKey) {
            while (!frameQueue.offer(frame)) {
                // Evict oldest P-frame. If only keyframes queued, drop oldest keyframe.
                val evicted = frameQueue.poll()
                // CONFIG 不进账，驱逐它不记丢；视频帧（含关键帧）逐帧销账（issue #205）。
                if (evicted != null && !evicted.isConfig) intake.onDropped(evicted.isKeyFrame)
                val droppedNow = intake.snapshot()
                // 被驱逐的帧（无论是 P 还是关键帧）都在参考链上留了洞：
                // 进入重同步，队列里剩余的陈旧 P 帧一律不喂。
                noteGap()
                if (droppedNow.dropped <= 3 || droppedNow.dropped % 60 == 0L) {
                    logW("Queue full — evicted for KEYFRAME #${droppedNow.dropped} (keys_dropped=${droppedNow.keyDropped})")
                }
                if (evicted == null) break
            }
            return
        }

        // P-frame: try to enqueue, drop if full
        if (!frameQueue.offer(frame)) {
            intake.onDropped(isKey = false)
            // 丢 P 帧 = 参考链有洞，后续 P 帧不可信 → 重同步到下一个 IDR。
            noteGap()
            val droppedNow = intake.snapshot()
            if (droppedNow.dropped <= 5 || droppedNow.dropped % 60 == 0L) {
                logW("Queue full — dropped incoming P-frame #${droppedNow.dropped} (receive=$receivedNow)")
            }
        }
    }

    /**
     * Switches the decoder output to a new Surface without stopping/restarting.
     * Uses MediaCodec.setOutputSurface() — available since API 23 (Android 10+ BYD).
     * Eliminates the keyframe-drop storm that happens with stop()+start().
     *
     * 返回**真实结果**（issue #198）：调用方此前无从判断重绑是否成功，于是在失败时
     * 也打「switched」成功日志，渲染闸门永久关闭 → 整段黑屏且日志看不出问题。
     * 失败时调用方必须回退到重建解码器。
     */
    fun switchSurface(newSurface: Surface): Boolean {
        if (!newSurface.isValid) {
            logW("switchSurface: newSurface is not valid, ignoring")
            return false
        }
        synchronized(codecLock) {
            val c = codec
            if (c == null) {
                logW("switchSurface: codec is null, ignoring")
                return false
            }
            if (!running.get()) {
                logW("switchSurface: decoder is not running, ignoring")
                return false
            }
            if (!newSurface.isValid) {
                logW("switchSurface: newSurface became invalid before switch, ignoring")
                return false
            }
            try {
                c.setOutputSurface(newSurface)
                outputSurfaceValid = true
                // 新 Surface 上还没出过帧，STREAMING 门槛要重新攒（issue #198）。
                framesRenderedOnSurface = 0
                log("Switched decoder output to new Surface")
                return true
            } catch (e: Exception) {
                logE("switchSurface failed: ${e.message}")
                return false
            }
        }
    }

    /** Mark the output surface invalid (destroyed). The decoder keeps running;
     *  releaseOutputBuffer's render flag is gated off until switchSurface/start
     *  re-attaches a valid surface. */
    fun invalidateSurface() {
        outputSurfaceValid = false
        // 旧 Surface 上的出帧数不再代表「当前有可见输出」（issue #198）。
        framesRenderedOnSurface = 0
    }

    /**
     * Stops the decoder and releases the MediaCodec.
     *
     * Serialized against [start] by [lifecycleLock] (audit S-08). After
     * joining the feed thread, the release is only performed when the thread is
     * verifiably gone — `interrupt()` cannot break a native
     * `dequeueOutputBuffer` call, and releasing under it is the documented
     * SIGSEGV pattern this repo works around on the vd-server side with a
     * watchdog (audit S-07).
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!running.getAndSet(false)) return
            outputSurfaceValid = false
            // issue #205：先把队列里和缓存里「已接收、未喂入」的帧记成丢弃，再把汇总行打出来
            // —— 否则每次 stop 都会静默吞掉最多 8 帧 + 1 个缓存 IDR，账永远差在身上。
            while (true) {
                val queued = frameQueue.poll() ?: break
                // CONFIG 不进账（它从未被 onReceived 记过），只结视频帧的账。
                if (!queued.isConfig) intake.onDropped(queued.isKeyFrame)
            }
            if (cachedKeyFrameStore.clear()) intake.onDropped(isKey = true)
            val stopSnap = intake.snapshot()
            log("Stopping decoder: fed=${stopSnap.fed} rendered=$renderCount drops=${stopSnap.dropped} inputFails=$inputFailCount codec generation=$codecGeneration")
            resetBlackScreenState()
            liveness.reset()
            contentBlackHeal.reset()
            resync.reset()

            val thread = feedThread
            feedThread = null
            thread?.interrupt()
            try { thread?.join(2000) } catch (_: InterruptedException) {}

            if (thread != null && thread.isAlive) {
                // The feed thread is wedged in a native MediaCodec call. It still
                // owns the codec it captured at start(); its finally will release
                // that instance once it unwinds. Releasing here would pull the
                // instance out from under a live native call — the documented
                // SIGSEGV pattern this repo works around on the vd-server side
                // with a watchdog (audit S-07). Leave the field alone; the
                // thread's finally clears nothing but does release its own ref.
                logW("Feed thread still alive after 2s join — leaving codec release to the thread's finally (codec generation=$codecGeneration)")
                codec = null
                return
            }

            // Normal path: the feed thread already exited, so its `finally` has
            // released the codec it captured. Releasing here again would only
            // raise IllegalStateException on an already-released instance —
            // the thread is the single owner of the release (audit S-07).
            codec = null
            codecGeneration = 0
            // issue #205：会话结束时对账 —— 队列已清、线程已退出，pending 必须归零。
            intake.violationText()?.let { logW("Intake accounting mismatch after stop: $it") }
            val pending = intake.snapshot().pending
            if (pending != 0L) logW("Intake accounting leak after stop: pending=$pending")
        }
    }

    /**
     * 重建解码器的**唯一**入口（issue #205）：`stop()` + `start()`，generation 随之递增；
     * 重建成功后在这里补请求一次 IDR（issue #207 欠账）。
     *
     * 所有「Surface 换了 / 重绑失败要重来」的分支都走这里，保证旧 codec 一定先被释放、
     * 新 codec 一定拿到新代号，日志里 created/released 可以严格配对。目前调用方是
     * #198 的 `switchSurface` 失败回退；黑屏重建与 viewport 重握手只重连、不重建解码器，
     * 语义不变。
     */
    fun recreateDecoder(surface: Surface, width: Int, height: Int, fps: Int, reason: String) {
        logW("Recreating decoder: reason=$reason")
        stop()
        // 冷启动请求让位给下面的 requestSyncAfterRebuild（#207 唯一请求点）。
        start(surface, width, height, fps, requestIdrOnColdStart = false)
        // issue #207 欠账：`stop()` 已经清掉缓存 IDR，新 codec 没有参考帧，不补一拍
        // IDR 就要等下一个自然 GOP（最多 ~1.2s 黑屏）。请求只走 onNeedsSyncFrame →
        // `CarSessionCoordinator.requestSyncFrame` → `SyncRequestGate`（#75/#163 限流），
        // 视频层不自己发协议帧，也不绕过限流。
        requestSyncAfterRebuild(rebuilt = running.get(), reason = reason)
    }

    /**
     * 重建成功后的 IDR 补偿（单个调用点，保证「一次重建只发一次请求」）。
     *
     * 重建失败（`start()` 没把 running 置回来）时不请求：此时没有 codec 可解，请求 IDR
     * 只会白占 `SyncRequestGate` 的窗口。`internal` 供单测用假 gate 计数。
     */
    internal fun requestSyncAfterRebuild(rebuilt: Boolean, reason: String) {
        if (!rebuilt) return
        log("requesting IDR (reason=$reason)")
        onNeedsSyncFrame?.invoke()
    }

    /**
     * 进程退出前的生命周期对账（issue #205）：非 0 时打 W 并列出代号。
     *
     * 由 `CarSessionCoordinator.shutdown()` 排在解码器执行器末尾调用 —— 在 `stop()` 之后、
     * 执行器结束之前，既能看到真实残留，也不会把「stop 还在排队」误报成泄漏。
     */
    fun logUnreleasedCodecs() {
        val outstanding = codecLedger.outstanding()
        if (outstanding == 0) {
            log("unreleased codecs=0 at shutdown")
        } else {
            logW("unreleased codecs=$outstanding at shutdown (generations=${codecLedger.liveGenerations()})")
        }
    }

    /**
     * 释放解码器的唯一出口（issue #205）：先 stop 再 release，各自吞异常，最后销账并打配对行。
     *
     * 两条路径共用它：喂帧线程的 finally（正常/异常退出）与 `start()` 里输给 `stop()` 的
     * 竞态回退。同一 generation 被释放两次会打 W —— 那正是「建 5 次只停 4 次」里最怕的情况。
     */
    private fun releaseCodec(decoder: MediaCodec, generation: Int, reason: String) {
        val known = releaseCodecQuietly(
            ledger = codecLedger,
            generation = generation,
            stop = { decoder.stop() },
            release = { decoder.release() },
        )
        val outstanding = codecLedger.outstanding()
        if (known) {
            log("codec generation=$generation released (reason=$reason, outstanding=$outstanding)")
        } else {
            logW("codec generation=$generation released twice / unknown (reason=$reason, outstanding=$outstanding)")
        }
    }

companion object {
        private const val TAG = "VideoDecoder"

        /**
         * 解码输入队列容量（issue #163）。
         *
         * 必须覆盖 [FeedBurst.MAX_FRAMES_PER_ROUND]（=8）：喂帧线程单轮最多连喂 8 帧，
         * 队列只有 4 槽时一次关键帧突发就必然 `offer` 失败 → `noteGap()` → 断链重同步，
         * 现场 8 分钟出现 9 次重同步、最长冻结 2.9s（跳过 87 个 P 帧）。
         *
         * 代价可控：单会话最多多占 4 帧内存（1204x660 的 IDR ≈160KB，P 帧 1–10KB）。
         * 守护测试见 `VideoDecoderQueueCapacityTest`。
         */
        internal const val FRAME_QUEUE_CAPACITY = 8

        /**
         * 解码器生命周期行（`codec generation=N created/released`、`Stopping decoder`）的统一标记
         * （issue #205）。
         *
         * 这些行恰好产生在断线瞬间：控制链路已断 → 只能进 `CarLogWriter` 离线缓冲 → 下个会话
         * `beginSession` 会清掉整个缓冲。现场「建 5 次只停 4 次」正是这么被清出来的假象，
         * 所以把标记交给 `beginSession` 跨会话保留，created/released 才有机会配对。
         */
        internal const val LIFECYCLE_LOG_MARKER = "codec generation="

        /**
         * How long the stream must stay black before [onSustainedBlackScreen]
         * fires. Long enough to ride out app-launch and VD warm-up transients,
         * short enough that a real stuck stream is recovered while the user is
         * still looking at it.
         */
        private const val BLACK_SCREEN_SUSTAIN_MS = BlackScreenDetector.BLACK_SCREEN_SUSTAIN_MS

        /**
         * 闸门抑制日志的最小间隔（issue #11）。
         *
         * 黑屏满窗后每来一帧都会问一次闸门，关键帧约 1.2s 一个；同一段内容黑可能持续
         * 几十秒，逐帧打日志只会把车机日志冲掉。5s 一条足以证明闸门在工作。
         */
        private const val ALIVE_GATE_LOG_INTERVAL_MS = 5_000L

        /**
         * Clamp a requested frame rate into the protocol's declared range
         * (audit S-L2).
         *
         * The value reaches [start] from a persisted preference and is used
         * twice as a divisor (`1000L / fps`) — `0` (reachable from a legacy or
         * hand-edited pref) raised an `ArithmeticException` inside the feed
         * thread, which killed decoding with no recovery. Kept as a pure
         * function so it is unit-testable without MediaCodec.
         */
        internal fun coerceFps(fps: Int): Int = fps.coerceIn(VideoConfig.MIN_FPS, VideoConfig.MAX_FPS)
    }
}
