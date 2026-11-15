package com.dilinkauto.server.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.dilinkauto.protocol.AUDIO_JITTER_BUFFER_DEFAULT_MS
import com.dilinkauto.protocol.AudioJitterBuffer
import com.dilinkauto.protocol.AudioPayload
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 播放 9640 上来的 AAC-LC 音频（issue #73 分节 PB / Refs #13）。
 *
 * ## 形状照 [VideoDecoder]
 *
 * 网络线程只入队（[onFrame] 必须非阻塞），专用播放线程按节拍消费；[start] / [stop]
 * 整段由 [lifecycleLock] 串行；`stop()` 只做**有界 join**，「线程是 release 唯一属主」
 * —— 播放线程捕获它自己创建的 `MediaCodec` / `AudioTrack`，在 `finally` 里释放，
 * `stop()` 绝不越过活着的线程去 release（与 `VideoDecoder.stop()` 同一口径）。
 *
 * ## 与 VideoDecoder 的三处关键差异
 *
 * 1. **按 21.333 ms / 46.875 fps 的节拍消费，不是 20 ms**。AAC-LC 48 kHz 每帧 1024
 *    samples = 21.333 ms，这是硬约束：按 20 ms 走会每秒多播 7~8 帧、队列膨胀
 *    （#13 §2.5 R13 实测）。
 * 2. **两条补静音的路**：①队列空（没数据可解）→ 写一帧静音并计 `starved`；②有输入但
 *    解码器本拍没吐 PCM（`INFO_TRY_AGAIN_LATER`）→ 按低水位补静音并计 `padIdle`
 *    （issue #164）。音频丢一帧就是 21.333 ms 空洞，不像视频能靠 IDR 重建，所以没有
 *    丢帧策略，只有「补静音」兜底。
 * 3. **`AudioTrack.getUnderrunCount()` 单独计 `underrun`**（底层欠载），与 `starved`
 *    （应用层队列空）是两个不同口径，e2e 两个都要看。
 *
 * 抖动缓冲的状态机（目标水位、起播裁水位、队列上限丢最旧、补静音判定）抽在
 * `:protocol-core` 的 `AudioJitterBuffer` 纯函数里，本类只留 `MediaCodec` / `AudioTrack`
 * 的 I/O 与节拍。
 *
 * 所有失败都只记日志并退出播放线程，**绝不向外抛**（R6：音频失败不许影响视频会话）。
 */
class AudioPlayer(
    /**
     * 抖动缓冲毫秒值。默认 150 ms（#13 §2.5 的 15 组实测拐点），构造参数可配，
     * 由 [AudioJitterBuffer] 钳进 60~200 ms。
     *
     * 不挂到 `CarPrefs`：S4 只落地了 `audioEnabled`，本节不新增持久化项，故用
     * 构造参数 + 常量默认值。
     */
    private val bufferMs: Int = AUDIO_JITTER_BUFFER_DEFAULT_MS
) {

    /** 日志回调 —— 由 CarConnectionService 接到 `carLogSend`（同 `VideoDecoder.logSink`）。 */
    var logSink: ((String) -> Unit)? = null

    /**
     * 播放线程因不可恢复原因退出时的显式上报（issue #181）。
     *
     * 原来 CONFIG 超时只在线程内打一行日志就返回，上层完全不知道播放侧已经死了，
     * 喂帧侧继续填 jitter 队列。该回调由 `CarConnectionService` 接到 `carLogSend(..., "E")`，
     * 让「播放不可用」在会话层可见。
     */
    var onFatal: ((String) -> Unit)? = null

    private fun emit(line: String) {
        logSink?.invoke(line) ?: Log.i(TAG, line)
    }

    private fun log(msg: String) = emit("[AudioPlayer] $msg")
    private fun logW(msg: String) = emit("[AudioPlayer][W] $msg")
    private fun logE(msg: String) = emit("[AudioPlayer][E] $msg")

    /**
     * Guards start()/stop() as whole critical sections（同 `VideoDecoder.lifecycleLock`）。
     * `MediaCodec` / `AudioTrack` 的生命周期跨线程：网络协程配 CONFIG、服务线程启停，
     * 没有这把锁会出现「stop 释放了还没人喂的 codec」之类竞态。
     */
    private val lifecycleLock = Any()

    private val running = AtomicBoolean(false)

    /**
     * 播放侧是否已判定不可用（issue #181）。
     *
     * 致命退出（CONFIG 硬超时 / 解码器或 `AudioTrack` 创建失败）后置 true，
     * [onFrame] 据此不再入队 —— 否则喂帧侧会继续把 jitter 队列填满、持续刷丢帧日志，
     * 而播放线程已经不在了。**不改丢帧策略本身**（丢最旧帧属 #179）。
     */
    @Volatile
    private var playbackFailed = false

    /** 播放线程是否在跑。 */
    val isRunning: Boolean get() = running.get()

    /** 播放侧是否已判定不可用（issue #181，诊断/测试用）。 */
    val isPlaybackFailed: Boolean get() = playbackFailed

    /** 当前会话的抖动缓冲；未 [start] 时为 null（[onFrame] 直接丢弃）。 */
    @Volatile
    private var jitter: AudioJitterBuffer<AudioPayload.Frame>? = null

    private var playbackThread: Thread? = null

    /**
     * 最近的 CONFIG。**总是缓存**，即使播放器还没 start —— CONFIG 必须先于 FRAME，
     * 丢了它后面的裸 AAC 帧都解不了。由网络线程写、播放线程读，故 @Volatile。
     */
    @Volatile
    private var config: AudioPayload.Config? = null

    /** 每次 [configure] 自增；播放线程据此重建解码器（编码器重配置时会再发 CONFIG）。 */
    @Volatile
    private var configGeneration = 0

    /** CONFIG 之前收到的 FRAME 数（正常应为 0），只用于日志限流。 */
    private val framesBeforeConfig = AtomicLong(0)

    // ── 诊断计数（e2e / 周期日志） ──

    private val decodedFrames = AtomicLong(0)
    private val starvedFrames = AtomicLong(0)
    private val silenceFrames = AtomicLong(0)
    private val maxRecoverMsValue = AtomicLong(0)

    /**
     * 累计由「有输入但本拍无输出」这条路径补的静音帧（issue #164）。
     *
     * 与 [starvedFrames] 分开计数：`starved` 是队列空（没数据），这里是有数据但解码器
     * 本拍没吐出来 —— 下次 `underrun` 再涨时靠这个字段区分是网络供不上还是节拍对不齐。
     */
    private val padIdleFrames = AtomicLong(0)

    /** 最近一次读到的 `AudioTrack.getUnderrunCount()`。 */
    @Volatile
    private var underrunValue = 0

    /** 当前队列深度（帧）。 */
    @Volatile
    private var queueDepthValue = 0

    /** 因抖动缓冲满而丢弃的最旧帧累计数。 */
    @Volatile
    private var droppedOldestValue = 0L

    /** 因抖动缓冲/输入缓冲不可用而丢掉的帧（正常为 0）。 */
    @Volatile
    private var inputDropValue = 0L

    /** 累计解码帧数（不含 CONFIG）。 */
    val decoded: Long get() = decodedFrames.get()

    /** 累计欠载补静音帧数。 */
    val starved: Long get() = starvedFrames.get()

    /** 累计解码出的全零静音帧数（issue #84 / Refs #79）。 */
    val silence: Long get() = silenceFrames.get()

    /** 累计「有输入但本拍无输出」补的静音帧数（issue #164）。 */
    val padIdle: Long get() = padIdleFrames.get()

    /** `AudioTrack.getUnderrunCount()` 的最新值。 */
    val underrun: Int get() = underrunValue

    /** 最长一次「欠载 → 恢复到目标水位」的耗时（ms）。 */
    val maxRecoverMs: Long get() = maxRecoverMsValue.get()

    /** 当前队列深度（帧）。 */
    val queue: Int get() = queueDepthValue

    /** 丢最旧帧累计数。 */
    val droppedOldest: Long get() = droppedOldestValue

    /** 因输入缓冲不可用而丢弃的帧累计数（诊断）。 */
    val inputDrop: Long get() = inputDropValue

    /**
     * 缓存一帧 CONFIG（网络线程调用）。可早于 [start]。
     *
     * CONFIG 带 `csd-0`，是解码器唯一的初始化来源；每次到达都让播放线程重建解码器
     * （编码器重配置时会重发）。
     */
    fun configure(config: AudioPayload.Config) {
        this.config = config
        configGeneration++
        log("CONFIG received: ${config.sampleRate}Hz/${config.channelCount}ch bitrate=${config.bitrate} csd=${config.csd.size}B")
    }

    /**
     * 入队一帧裸 AAC（网络线程调用，必须非阻塞 —— 只做一次加锁入队）。
     *
     * FRAME 先于 CONFIG 到达时丢弃（裸 AAC 无 ADTS，缺 csd 解不了），并留一条痕迹。
     */
    fun onFrame(frame: AudioPayload.Frame) {
        if (!audioShouldEnqueueFrame(running.get(), playbackFailed, config != null)) {
            // CONFIG 还没到时留一条痕迹；播放侧已判死则整段静默，不再刷丢帧日志。
            if (running.get() && !playbackFailed) {
                val n = framesBeforeConfig.incrementAndGet()
                if (n <= 3) logW("FRAME before CONFIG (${frame.data.size}B) — dropped")
            }
            return
        }
        val buf = jitter ?: return
        val dropped = buf.enqueue(frame)
        if (dropped > 0) {
            droppedOldestValue = buf.droppedOldest
            if (droppedOldestValue <= 3 || droppedOldestValue % 60 == 0L) {
                logW("Jitter queue full — dropped $dropped oldest frame(s) (total=$droppedOldestValue, queue=${buf.size})")
            }
        }
    }

    /**
     * 启动播放线程。整段由 [lifecycleLock] 串行。
     *
     * 不在这里创建 `MediaCodec` / `AudioTrack`：它们需要 CONFIG（采样率/声道/csd），
     * 由播放线程在拿到 CONFIG 后创建，并在自己的 `finally` 里释放（release 唯一属主）。
     */
    fun start() {
        synchronized(lifecycleLock) {
            if (running.getAndSet(true)) {
                logW("start() called but already running")
                return
            }
            val buf = AudioJitterBuffer<AudioPayload.Frame>(bufferMs)
            jitter = buf
            decodedFrames.set(0)
            starvedFrames.set(0)
            silenceFrames.set(0)
            maxRecoverMsValue.set(0)
            padIdleFrames.set(0)
            underrunValue = 0
            queueDepthValue = 0
            droppedOldestValue = 0
            inputDropValue = 0
            framesBeforeConfig.set(0)
            playbackFailed = false

            log("Starting: bufferMs=${buf.bufferMs} targetFrames=${buf.targetFrames} capacity=${buf.capacity}")

            playbackThread = Thread({ playbackLoop(buf) }, "AudioPlayerPlayback").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 停止播放线程并释放 `MediaCodec` / `AudioTrack`。
     *
     * 有界 join + 「线程是 release 唯一属主」：join 超时后**不**从本线程 release，
     * 宁可让线程的 `finally` 自己收尾（同 `VideoDecoder.stop()` 的 SIGSEGV 规避）。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!running.getAndSet(false)) return
            log("Stopping: decoded=${decodedFrames.get()} starved=${starvedFrames.get()} silence=${silenceFrames.get()} underrun=$underrunValue maxRecoverMs=${maxRecoverMsValue.get()} queue=$queueDepthValue padIdle=${padIdleFrames.get()}")
            emitStatLine()
            jitter?.clear()
            jitter = null

            val t = playbackThread
            playbackThread = null
            t?.interrupt()
            try { t?.join(STOP_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
            if (t != null && t.isAlive) {
                // 播放线程卡在 native 调用里；它捕获的 track/decoder 由它自己的 finally
                // 释放。此处 release 会把实例从活着的 native 调用下抽走 —— 那正是本仓库
                // 在 vd-server 侧用 watchdog 规避的 SIGSEGV 形态。
                logW("Playback thread still alive after ${STOP_JOIN_TIMEOUT_MS}ms join — leaving release to the thread's finally")
            }
        }
    }

    // ── 播放线程 ──

    private fun playbackLoop(buf: AudioJitterBuffer<AudioPayload.Frame>) {
        // 8x A53 无大核：告诉调度器这是延迟敏感线程，别被后台工作饿死。
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            // issue #181：软阈值只告警、硬阈值才终结；超时由 awaitFirstConfig 内显式上报。
            val firstConfig = awaitFirstConfig(
                configSource = { config },
                runningSource = { running.get() },
                clockMs = { SystemClock.elapsedRealtime() },
                parkNanos = { LockSupport.parkNanos(it) },
            ) ?: return
            // 已应用的 CONFIG 代次：每次 configure 都会 +1，据此决定要不要重建解码器。
            var appliedGeneration = configGeneration

            codec = buildDecoder(firstConfig)
            if (codec == null) {
                reportFatal("decoder fatal: Failed to create AAC decoder — audio unavailable for this session")
                return
            }
            track = buildTrack(firstConfig)
            if (track == null) {
                reportFatal("track fatal: Failed to create AudioTrack — audio unavailable for this session")
                return
            }
            track.play()
            log("AudioTrack playing: ${firstConfig.sampleRate}Hz/${firstConfig.channelCount}ch")

            val pcmFrameBytes = PCM_SAMPLES_PER_FRAME * firstConfig.channelCount * BYTES_PER_SAMPLE
            // 一帧的节拍：1024 samples / 采样率。48 kHz → 21 333 333 ns（不是 20 ms）。
            val frameNanos = PCM_SAMPLES_PER_FRAME.toLong() * 1_000_000_000L / firstConfig.sampleRate
            val silence = ByteArray(pcmFrameBytes)
            // AudioTrack 的**实际**容量（帧）。ROM 会把请求值向上取整，水位必须按实际值算，
            // 否则「已满不补」的闸门会提前或延后失效（issue #164）。
            val trackBufferFrames = track.bufferSizeInFrames.coerceAtLeast(1)
            // 累计写进 track 的帧数；与 playbackHeadPosition 求差 = 还没播出去的积压。
            // 只用于给补静音封顶，**不用来校准写入节奏**（那是 issue #164 的候选 C，未做）。
            var writtenTrackFrames = 0L

            primeUp(buf)

            val info = MediaCodec.BufferInfo()
            var nextTick = System.nanoTime()
            var ptsUs = 0L
            var lastStatMs = SystemClock.elapsedRealtime()
            var recovering = false
            var starveStartMs = 0L

            while (running.get()) {
                nextTick = calculateNextTick(nextTick, System.nanoTime(), frameNanos)
                val sleep = nextTick - System.nanoTime()
                if (sleep > 0) LockSupport.parkNanos(sleep)

                // CONFIG 更新（编码器重配置）→ 重建解码器，别拿着旧 csd 继续解。
                if (configGeneration != appliedGeneration) {
                    val c = config
                    if (c != null) {
                        val freshly = buildDecoder(c)
                        if (freshly != null) {
                            try { codec?.stop() } catch (_: Exception) {}
                            try { codec?.release() } catch (_: Exception) {}
                            codec = freshly
                            appliedGeneration = configGeneration
                            log("Decoder reconfigured for new CONFIG")
                        }
                    }
                }

                val dec = codec ?: break
                val trk = track

                val frame = buf.poll()
                var wroteFramesThisTick = 0
                if (frame == null) {
                    starvedFrames.incrementAndGet()
                    if (!recovering) {
                        recovering = true
                        starveStartMs = SystemClock.elapsedRealtime()
                    }
                    // 队列空：沿用 #73 的「一拍补一帧」语义（口径不动，只计 starved）。
                    if (writePcm(trk, silence) > 0) wroteFramesThisTick = 1
                    writtenTrackFrames += wroteFramesThisTick
                } else {
                    if (feedDecoder(dec, frame, ptsUs)) {
                        ptsUs += frameNanos / 1000L
                        decodedFrames.incrementAndGet()
                    } else {
                        inputDropValue++
                    }
                    wroteFramesThisTick = drainDecoder(dec, trk, info) / pcmFrameBytes
                    writtenTrackFrames += wroteFramesThisTick
                    if (recovering && buf.size >= buf.targetFrames) {
                        recovering = false
                        val r = SystemClock.elapsedRealtime() - starveStartMs
                        if (r > maxRecoverMsValue.get()) maxRecoverMsValue.set(r)
                    }
                }

                // issue #164 候选 B：有输入、但解码器本拍没吐出任何 PCM（INFO_TRY_AGAIN_LATER），
                // 这一拍 track 一个字节都拿不到，只能吃老本。原来只有「队列空」才补，
                // 于是 85 ms 缓冲被一拍一拍啃到 0 —— 实车 22.6 次/秒 underrun 的直接来源。
                // 与上面的 `starved` 路互斥且口径不同：那条是「没数据可解」，这条是
                // 「有数据、但本拍解不出来」，所以单独计 `padIdle`，别混成一个数。
                if (wroteFramesThisTick == 0) {
                    val pad = audioPadSilenceFrames(
                        bufferFrames = trackBufferFrames,
                        pendingFrames = trackPendingFrames(trk, writtenTrackFrames, trackBufferFrames),
                        wroteFramesThisTick = 0,
                    )
                    if (pad > 0) {
                        var padded = 0
                        repeat(pad) { if (writePcm(trk, silence) > 0) padded++ }
                        writtenTrackFrames += padded
                        padIdleFrames.addAndGet(padded.toLong())
                    }
                }

                queueDepthValue = buf.size
                if (buf.droppedOldest > droppedOldestValue) droppedOldestValue = buf.droppedOldest
                // getUnderrunCount 是廉价读取，逐拍更新让周期行与终局行都拿到当前值。
                underrunValue = trk.underrunCount

                val now = SystemClock.elapsedRealtime()
                if (now - lastStatMs >= STAT_LOG_INTERVAL_MS) {
                    lastStatMs = now
                    emitStatLine()
                }
            }
        } catch (e: Throwable) {
            // 音频绝不许把异常穿到会话层（R6）；native 崩坏也只留一行 + 显式上报。
            reportFatal("playback fatal: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // 线程是它捕获的 codec/track 的唯一释放属主（无论正常退出还是抛异常）。
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    /**
     * 起播前等第一份 CONFIG，并处理软/硬阈值（issue #181）。
     *
     * 从 [playbackLoop] 抽出来是为了能在纯 JVM 单测里注入时钟，并锁住
     * 「`TimedOut` → [reportFatal] → [onFatal]」这条接线（删掉会红）。
     *
     * @return CONFIG；硬超时或 `stop()` 返回 null。
     */
    internal fun awaitFirstConfig(
        configSource: () -> AudioPayload.Config?,
        runningSource: () -> Boolean,
        clockMs: () -> Long,
        parkNanos: (Long) -> Unit,
        softTimeoutMs: Long = AUDIO_CONFIG_SOFT_TIMEOUT_MS,
        hardTimeoutMs: Long = AUDIO_CONFIG_HARD_TIMEOUT_MS,
    ): AudioPayload.Config? {
        val wait = AudioConfigWaiter(
            config = configSource,
            running = runningSource,
            clockMs = clockMs,
            parkNanos = parkNanos,
            softTimeoutMs = softTimeoutMs,
            hardTimeoutMs = hardTimeoutMs,
            onStillWaiting = { waited ->
                logW(
                    "No CONFIG within ${waited}ms — still waiting up to ${hardTimeoutMs}ms " +
                        "(playback stays silent until CONFIG arrives)"
                )
            },
            onTimeout = { waited ->
                reportFatal("CONFIG fatal: No CONFIG within ${waited}ms — audio unavailable for this session")
            },
        ).await()
        return when (wait) {
            is AudioConfigWaitResult.Ready -> wait.config
            is AudioConfigWaitResult.TimedOut, AudioConfigWaitResult.Stopped -> null
        }
    }

    /**
     * 致命退出：置 [playbackFailed]、放掉 [running]，并显式上报会话层（issue #181）。
     *
     * 必须同时 `running.set(false)`：否则同一连接下再次 `start()` 会被
     * `start() called but already running` 早退，[playbackFailed] 永远不会复位。
     */
    private fun reportFatal(reason: String) {
        running.set(false)
        playbackFailed = true
        logE(reason)
        onFatal?.invoke(reason)
    }

    /**
     * 起播前把积压裁到目标水位（探针 `rx2` 的 prime 语义）。
     *
     * 先等队列攒到 [AudioJitterBuffer.targetFrames]（有界），再裁掉多余的最旧帧 ——
     * 否则起播就背着几百毫秒延迟。
     */
    private fun primeUp(buf: AudioJitterBuffer<AudioPayload.Frame>) {
        val deadline = SystemClock.elapsedRealtime() + PRIME_TIMEOUT_MS
        while (running.get() && !buf.hasReachedTarget() && SystemClock.elapsedRealtime() < deadline) {
            LockSupport.parkNanos(2_000_000L)
        }
        val trimmed = buf.primeTrim()
        if (buf.droppedOldest > droppedOldestValue) droppedOldestValue = buf.droppedOldest
        log("Primed: depth=${buf.size}/${buf.targetFrames} trimmed=$trimmed")
    }

    private fun buildDecoder(config: AudioPayload.Config): MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.csd))
        }
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    } catch (e: Exception) {
        logE("Decoder create/configure failed: ${e.message}")
        null
    }

    private fun buildTrack(config: AudioPayload.Config): AudioTrack? = try {
        val channelMask = if (config.channelCount == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val frameBytes = PCM_SAMPLES_PER_FRAME * config.channelCount * BYTES_PER_SAMPLE
        val minBytes = AudioTrack.getMinBufferSize(
            config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBytes, frameBytes * TRACK_BUFFER_FRAMES)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            logE("AudioTrack state=${t.state} (uninitialized)")
            t.release()
            null
        } else {
            // 打出实际分配值：ROM 的 minBufferSize / 混音周期会把请求值向上取整，
            // 归因 underrun 时要按实际容量算，别拿请求值当真相（issue #164）。
            val frames = t.bufferSizeInFrames
            val actualMs = frames * PCM_SAMPLES_PER_FRAME.toLong() * 1000L / config.sampleRate
            log(
                "AudioTrack buffer: ${frames} frames ≈ ${actualMs}ms " +
                    "(${frames * frameBytes}B; requested=${bufferBytes}B/${bufferBytes / frameBytes} frames, min=$minBytes)"
            )
            t
        }
    } catch (e: Exception) {
        logE("AudioTrack build failed: ${e.message}")
        null
    }

    /** 把一帧裸 AAC 喂给解码器。返回 true 表示已入队。 */
    private fun feedDecoder(codec: MediaCodec, frame: AudioPayload.Frame, ptsUs: Long): Boolean = try {
        val inputId = codec.dequeueInputBuffer(0)
        if (inputId < 0) {
            false
        } else {
            val inBuf = codec.getInputBuffer(inputId)
            if (inBuf == null) {
                false
            } else {
                inBuf.clear()
                inBuf.put(frame.data)
                codec.queueInputBuffer(inputId, 0, frame.data.size, ptsUs, 0)
                true
            }
        }
    } catch (e: Exception) {
        logE("queueInputBuffer failed: ${e.message}")
        false
    }

    /** 排空解码输出并把 PCM 写进 AudioTrack；返回本拍**实际写入的字节数**（issue #164）。 */
    private fun drainDecoder(codec: MediaCodec, track: AudioTrack, info: MediaCodec.BufferInfo): Int {
        var wrote = 0
        while (true) {
            val outId = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                logE("dequeueOutputBuffer failed: ${e.message}")
                return wrote
            }
            if (outId >= 0) {
                val outBuf = try { codec.getOutputBuffer(outId) } catch (_: Exception) { null }
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    outBuf.get(pcm)
                    if (isPcmSilent(pcm)) {
                        silenceFrames.incrementAndGet()
                    }
                    wrote += writePcm(track, pcm)
                }
                try { codec.releaseOutputBuffer(outId, false) } catch (_: Exception) {}
            } else if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                log("Output format changed: ${codec.outputFormat}")
            } else {
                // INFO_TRY_AGAIN_LATER 等：本拍没有更多输出。返回已写字节数，
                // 调用方据此判断要不要补静音（issue #164）。
                return wrote
            }
        }
    }

    /** 写一段 PCM 进 track；返回实际写入的字节数（`write` 出错返回负数时按 0 计）。 */
    private fun writePcm(track: AudioTrack, pcm: ByteArray): Int = try {
        val n = track.write(pcm, 0, pcm.size)
        if (n > 0) n else 0
    } catch (e: Exception) {
        logE("AudioTrack.write failed: ${e.message}")
        0
    }

    /**
     * track 里「已写入但还没播出去」的帧数（issue #164）。
     *
     * `getPlaybackHeadPosition()` 是硬件播放头（单位：帧），`writtenFrames` 是本进程累计写入量。
     * 拿不到播放头（track 已释放/未初始化）时按「已满」返回 —— 让补静音的闸门关上，
     * 宁可少补也不无界堆延迟。
     */
    private fun trackPendingFrames(track: AudioTrack, writtenFrames: Long, bufferFrames: Int): Int = try {
        // 播放头在 Int 上回绕，按无符号扩展后再算差值。
        val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        audioTrackPendingFrames(writtenFrames, head, bufferFrames)
    } catch (_: Exception) {
        bufferFrames
    }

    private fun emitStatLine() {
        emit(
            audioPlayerStatLine(
                decoded = decodedFrames.get(),
                starved = starvedFrames.get(),
                underrun = underrunValue,
                maxRecoverMs = maxRecoverMsValue.get(),
                queue = queueDepthValue,
                silence = silenceFrames.get(),
                padIdle = padIdleFrames.get(),
            )
        )
    }

    companion object {
        private const val TAG = "AudioPlayer"

        /** PCM 一帧的 samples 数（AAC-LC 48 kHz 恒定 1024）。 */
        private const val PCM_SAMPLES_PER_FRAME = 1024
        private const val BYTES_PER_SAMPLE = 2

        /**
         * `AudioTrack` 缓冲的**请求下限**（帧）。12 帧 = 12 × 21.333 ms ≈ **256 ms**（issue #164）。
         *
         * 注意「下限」是相对 `buildTrack` 的 `maxOf(minBufferSize, 12 帧)` 说的：ROM 会把请求值
         * 向上取整，**实际分配容量可能更大**，`buildTrack` 的 `AudioTrack buffer: N frames ≈ Mms`
         * 那行日志打的是实际值，归因时看那行。
         *
         * 原来是 4 帧（85 ms），实车 `underrun` 涨到 22.6 次/秒：主循环每 21.333 ms 只写一次
         * PCM，解码器一次 `INFO_TRY_AGAIN_LATER` 就有一拍不写，85 ms 只够扛 4 拍。
         *
         * **为什么要 12 而不是 8**：实测 `maxRecoverMs=218`（最长一次「欠载 → 回到目标水位」
         * 的耗时），12 帧给这段最坏停顿留 38 ms 余量；16 帧只多付 85 ms 延迟、没有对应收益。
         *
         * **延迟从哪扣**：256 ms 是**容量上限**（写满会阻塞），不是稳态水位。
         * [AUDIO_TRACK_LOW_WATER_FRAMES] = 3 帧（64 ms）是**补静音的触发阈值，不是容量上限**：
         * 补帧最多把水位顶到 3 帧就停，真实 PCM 的突发（2~4 帧）会临时把水位抬到
         * 「3 + 突发帧数」，之后随播放自然回落 —— 无论哪种情况都不会无界增长。
         * 所以稳态新增的端到端延迟约 64 ms，不是 256 ms。放大容量上限换到的是两件事：
         * ①一次突发写 2~4 帧不会写满而让阻塞式 `write` 卡住节拍；
         * ②播放线程被调度走 200 ms 时不至于立刻见底。
         *
         * 不动 `AUDIO_JITTER_BUFFER_DEFAULT_MS`（150 ms）：`queue` 已长期满仓，放大抖动缓冲
         * 只会同时放大延迟与 `droppedOldest`，是掩盖不是修复（issue #164）。
         */
        private const val TRACK_BUFFER_FRAMES = 12

        /** 起播攒水位（prime）的上限。 */
        private const val PRIME_TIMEOUT_MS = 3000L

        /** 周期日志间隔（契约 2~3 s 一行，S6 用 `grep -cF` 命中）。 */
        private const val STAT_LOG_INTERVAL_MS = 2500L

        /** stop() 等播放线程退出的上限（同 `VideoDecoder.stop()` 的 2s 预算口径）。 */
        private const val STOP_JOIN_TIMEOUT_MS = 2000L

        /**
         * Pacing clock calculation with underrun/jitter catch-up prevention.
         * If [now] has drifted past [currentNextTick] by more than [frameNanos],
         * resets the baseline to [now] so the loop does not spin without sleeping.
         */
        internal fun calculateNextTick(currentNextTick: Long, now: Long, frameNanos: Long): Long {
            val base = if (now - currentNextTick > frameNanos) now else currentNextTick
            return base + frameNanos
        }
    }
}
