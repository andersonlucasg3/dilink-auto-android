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
    // Most recent IDR keyframe cached while the decoder is stopped. On rotation the
    // decoder stops before the fresh IDR arrives; without this cache the new codec
    // instance would have CONFIG but no reference frame, so P-frames render nothing
    // until the next live IDR (1-2s of black). start() feeds this right after CONFIG.
    @Volatile
    private var cachedKeyFrame: ByteArray? = null

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

    private var frameCount = 0L

    /**
     * 网络线程累计收到的帧数。喂帧线程读它算 STALL 增量（issue #58），跨线程可见性靠
     * `@Volatile`；它只做诊断，不作为控制流判据。
     */
    @Volatile
    private var receiveCount = 0L

    /**
     * 累计出帧数。喂帧线程在 drainOutput() 里自增，网络线程的黑屏闸门会读它
     * （issue #11），所以必须 @Volatile。只用于「是否还在增长」的比较，不需要原子自增。
     */
    @Volatile
    private var renderCount = 0L
    private var dropCount = 0L
    private var inputFailCount = 0L
    private var keyFramesReceived = 0L
    private var keyFramesFed = 0L
    private var keyFramesDropped = 0L

    /**
     * 重同步期间在**接收入口**被丢掉的 P 帧数（issue #200），与 [dropCount] 分开计数。
     *
     * [dropCount] 保留「参考链因积压/超时产生的洞」的原口径；这里的帧根本没进队列，
     * 不会占满 8 槽、也不会挤掉关键帧。`internal` 供行为级单测观察。
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
     */
    internal fun noteGap() {
        if (!resync.onGap()) return
        logW("Reference chain broken — waiting for next IDR (${dropCount} drops, ${inputFailCount} inputFails)")
        onNeedsSyncFrame?.invoke()
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
     */
    fun start(surface: Surface, width: Int, height: Int, fps: Int = VideoConfig.TARGET_FPS) {
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

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, effectiveFps)
            }

            // Re-check `running` AFTER creating the codec (audit S-08): a
            // concurrent stop() that interleaved before the lock was taken
            // (or a cancelled executor task) must not leave a live codec nobody
            // feeds. Release it right here instead.
            val newCodec: MediaCodec = try {
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

            if (!running.get()) {
                // Lost a race with stop(): tear down what we just built.
                logW("start(): stop() raced codec creation — releasing the fresh codec")
                try { newCodec.stop() } catch (_: Exception) {}
                try { newCodec.release() } catch (_: Exception) {}
                codec = null
                return
            }

            outputSurfaceValid = true
            // 新解码器实例还没有在它绑定的 Surface 上出过帧（issue #198）。
            framesRenderedOnSurface = 0
            log("MediaCodec created: name=${newCodec.name} dims=${width}x${height} operatingRate=$effectiveFps")

            frameCount = 0
            renderCount = 0
            dropCount = 0
            inputFailCount = 0
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

                    // Feed the cached IDR (captured while the decoder was stopped) so the
                    // codec has a reference frame immediately and P-frames render without
                    // waiting for the next live IDR. seekingKeyFrame stays false — this
                    // keyframe IS the reference, not a post-flush resync.
                    cachedKeyFrame?.let { key ->
                        log("Feeding cached KEYFRAME for cold-start reference (${key.size} bytes)")
                        feedBuffer(decoder, key, 0)
                        cachedKeyFrame = null
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
                            if (frameCount == 0L) log("Waiting for CONFIG before feeding video frames")
                            return
                        }
                        val isKey = frame.isKeyFrame
                        // 参考链有洞时（issue #9），MediaCodec 用坏参考解 P 帧只会产马赛克。
                        // 停喂 P 帧、只等 IDR：onFrameReceived 给关键帧队列优先，下一个 IDR
                        // 很快到队头；能索要时还会经 onNeedsSyncFrame 让手机立刻补一个。
                        if (!resync.shouldFeed(isKey)) {
                            skipCount++
                            if (skipCount <= 5 || skipCount % 30 == 0L) {
                                logW("Resync: skipping P-frame #$skipCount until next IDR")
                            }
                            return
                        }
                        if (skipCount > 0) {
                            log("Resync: resynced at IDR keyframe after skipping $skipCount P-frames")
                            skipCount = 0L
                        }
                        if (isKey) {
                            keyFramesFed++
                            log("Feeding KEYFRAME #$keyFramesFed size=${frame.data.size} (fed=$frameCount rendered=$renderCount)")
                        }
                        val decodeStart = if (debugFrameStats) System.nanoTime() else 0L
                        val fed = feedBuffer(decoder, frame.data, 0)
                        if (debugFrameStats) windowDecodeNanos += System.nanoTime() - decodeStart
                        if (fed) {
                            frameCount++
                        } else {
                            dropCount++
                        }
                        if (frameCount % 30 == 0L) {
                            if (debugFrameStats) {
                                val decodeMs = windowDecodeNanos / 1_000_000.0
                                log("Fed $frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount keys_recv=$keyFramesReceived keys_fed=$keyFramesFed keys_drop=$keyFramesDropped queue=${frameQueue.size} skips=$skipCount resync=${resync.awaitingIdr} decodeMs=${"%.1f".format(decodeMs)}")
                                windowDecodeNanos = 0L
                            } else {
                                log("Fed $frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount keys_recv=$keyFramesReceived keys_fed=$keyFramesFed keys_drop=$keyFramesDropped queue=${frameQueue.size} skips=$skipCount resync=${resync.awaitingIdr}")
                            }
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
                        renderStall.onTick(
                            nowMs = tickNowMs,
                            snap = RenderStallDetector.Snapshot(
                                renders = renderCount,
                                receive = receiveCount,
                                fed = frameCount,
                                drops = dropCount,
                                inputFails = inputFailCount,
                                keysDropped = keyFramesDropped,
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
                                    logDiagW("LINK GAP ${r.gapMs}ms — no render advance (renders=$renderCount recv=$receiveCount fed=$frameCount drops=$dropCount queue=${frameQueue.size} ctrlSilent=$ctrlSilent) — pre-arming resync + one IDR request")
                                    noteGap()
                                }
                                StallEscalationPolicy.Level.STALLED ->
                                    logDiagW("STALLED ${r.gapMs}ms — still no render advance (renders=$renderCount recv=$receiveCount fed=$frameCount drops=$dropCount inputFails=$inputFailCount queue=${frameQueue.size} ctrlSilent=$ctrlSilent resync=${resync.awaitingIdr})")
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
                    log("Feed thread exiting: fed=$frameCount rendered=$renderCount drops=$dropCount")
                } finally {
                    // The thread owns the codec it captured (audit S-07): release
                    // it here whether we exited normally or threw. This is the
                    // only release path that is guaranteed to run *after* this
                    // thread has left every native MediaCodec call.
                    synchronized(codecLock) {
                        try { decoder.stop() } catch (_: Exception) {}
                        try { decoder.release() } catch (_: Exception) {}
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
        receiveCount++
        val isKey = !isConfig && isKeyFrame(data)
        if (isKey) keyFramesReceived++
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
        if (isConfig || isKey || receiveCount <= 3 || receiveCount % 60 == 0L) {
            log("onFrameReceived #$receiveCount isConfig=$isConfig isKey=$isKey size=${data.size} running=${running.get()} queue=${frameQueue.size}")
        }
        // Always cache CONFIG — needed to bootstrap the next decoder instance.
        // A fresh CONFIG establishes a new stream boundary, so any stale cached
        // keyframe from the previous stream must be discarded.
        if (isConfig) {
            configData = data
            cachedKeyFrame = null
        }
        // When stopped, cache the most recent IDR so start() can feed it as a
        // reference frame. P-frames are dropped (no codec to decode them).
        if (!running.get() && !isConfig) {
            if (isKey) {
                cachedKeyFrame = data
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
            if (resyncSkippedFrames <= 5 || resyncSkippedFrames % 60 == 0L) {
                logW("Resync: dropped P-frame at receive #$resyncSkippedFrames until next IDR (receive=$receiveCount queue=${frameQueue.size})")
            }
            return
        }
        val frame = FrameData(isConfig, isKey, data)

        // Keyframe priority: if queue has frames, drop P-frames to make room
        if (isConfig || isKey) {
            while (!frameQueue.offer(frame)) {
                // Evict oldest P-frame. If only keyframes queued, drop oldest keyframe.
                val evicted = frameQueue.poll()
                dropCount++
                if (evicted != null && evicted.isKeyFrame) keyFramesDropped++
                // 被驱逐的帧（无论是 P 还是关键帧）都在参考链上留了洞：
                // 进入重同步，队列里剩余的陈旧 P 帧一律不喂。
                noteGap()
                if (dropCount <= 3 || dropCount % 60 == 0L) {
                    logW("Queue full — evicted for KEYFRAME #$dropCount (keys_dropped=$keyFramesDropped)")
                }
                if (evicted == null) break
            }
            return
        }

        // P-frame: try to enqueue, drop if full
        if (!frameQueue.offer(frame)) {
            dropCount++
            // 丢 P 帧 = 参考链有洞，后续 P 帧不可信 → 重同步到下一个 IDR。
            noteGap()
            if (dropCount <= 5 || dropCount % 60 == 0L) {
                logW("Queue full — dropped incoming P-frame #$dropCount (receive=$receiveCount)")
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
            log("Stopping decoder: fed=$frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount")
            resetBlackScreenState()
            liveness.reset()
            contentBlackHeal.reset()
            resync.reset()
            frameQueue.clear()
            cachedKeyFrame = null

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
                logW("Feed thread still alive after 2s join — leaving codec release to the thread's finally")
                codec = null
                return
            }

            // Normal path: the feed thread already exited, so its `finally` has
            // released the codec it captured. Releasing here again would only
            // raise IllegalStateException on an already-released instance —
            // the thread is the single owner of the release (audit S-07).
            codec = null
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
