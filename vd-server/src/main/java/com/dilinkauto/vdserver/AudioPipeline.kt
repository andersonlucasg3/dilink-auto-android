package com.dilinkauto.vdserver

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import com.dilinkauto.protocol.AudioPayload
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 音频采集 + AAC-LC 编码管线（issue #13 方案 A / 分节 S2）。
 *
 * ## 为什么能用 REMOTE_SUBMIX
 *
 * vd-server 以 **shell UID** 跑 `app_process`，shell 自带 `CAPTURE_AUDIO_OUTPUT`，因此
 * 可以直接 `AudioRecord(REMOTE_SUBMIX)` 采整机输出 —— 不需要 app 运行时授权、不需要
 * MediaProjection、不受国产 ROM 的 app 侧限制（#13 E1/E3 实测：HyperOS 14 与
 * AOSP-like 15 都放行）。代价是**手机本机静音**（MI 9 播放端 `deviceId:507` ==
 * `Ports ID:507 "Remote Submix Out"`，E9），这是已接受的预期行为。
 *
 * ## 设计约束
 *
 * - **绝不拖垮视频会话（R6）**：[start] 的任一环节失败都只记日志并返回 `false`，主循环
 *   整段 try/catch 兜底。音频是可选能力，采不到就不采。
 * - **不丢真实帧**：与 `VideoFrameSender` 的「直播优先丢 P 帧」不同，音频丢一帧就是
 *   21.333 ms 的空洞，所以这里没有丢帧策略，只有欠载补静音。
 * - **必须凑满一整帧再喂编码器**：`READ_NON_BLOCKING` 可能部分读，把半帧直接喂给
 *   `MediaCodec` 会让出帧节奏崩掉（#13 探针实测：帧率异常、播放端队列膨胀）。
 * - **CONFIG 必须先于 FRAME**：`INFO_OUTPUT_FORMAT_CHANGED` 时取 `csd-0` 发
 *   [AudioPayload.Config]，之后才允许发 [AudioPayload.Frame]；编码器重配置要重发。
 *
 * 出帧通过注入的 [Sink] 交给上层；S2 不含 socket 与播放（属 S3）。
 */
internal class AudioPipeline(
    private val spec: AudioSpec = AudioSpec.DEFAULT,
    private val sink: Sink,
    /**
     * issue #165：会话是否仍在运行（由 [PipelineServer] 传 `{ running }`）。
     *
     * 本类自己的 `running` 只在 [close] 里翻 false，而 `close()` 位于 cleanup 的**最末段**；
     * cleanup 开头置 `PipelineServer.running=false` 后，采集线程原本还要在「letterbox 复位 /
     * 搬 app / 恢复面板 IME / shell 屏障」这几秒里继续 read REMOTE_SUBMIX 并投喂编码器。
     * 把它接进采集闸门后，收尾一开始就停止投喂。
     */
    private val sessionRunning: () -> Boolean = { true },
    private val log: (String) -> Unit = { PipeLog.log(it) },
    private val logErr: (String) -> Unit = { PipeLog.err(it) },
    private val clockNanos: () -> Long = { System.nanoTime() }
) : AutoCloseable {

    /** 出帧汇：S3 在这里接 9640 socket，S6 在这里数帧。 */
    internal interface Sink {
        /** 编码参数（含带外 `csd-0`）。任何一帧 FRAME 之前必须先收到它。 */
        fun onConfig(config: AudioPayload.Config)

        /** 一帧裸 AAC-LC。 */
        fun onFrame(frame: AudioPayload.Frame)

        /**
         * issue #165：写端是否已经判死（Broken pipe / connection abort 等）。
         *
         * 默认 false（内存/测试 sink 不会失败）；[SocketAudioSink] 用写线程的 `failed` 覆写。
         * 一旦为 true，采集侧必须停止投喂 —— 接收端已经没了，继续编码只会制造
         * `queueInputBuffer failed: CodecException` 噪声。
         */
        fun isFailed(): Boolean = false
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var record: AudioRecord? = null

    @Volatile private var codec: MediaCodec? = null

    /** 只由采集线程访问：编码器上报的 `csd-0`。 */
    private var csd0: ByteArray? = null

    /** 只由采集线程访问：`csd-0` 有更新、还没随 CONFIG 发出去。 */
    private var csdDirty = false

    private var warnedMissingCsd = false

    private val frames = AtomicLong(0)
    private val silenceFrames = AtomicLong(0)
    private val droppedInputs = AtomicLong(0)

    /**
     * issue #194：发送侧诊断统计（编码前 PCM 是否全 0、编码后单帧字节数）。
     * 只由采集线程访问；每 100 帧打一行 `AudioDiag tx ...`。
     *
     * 2026-11-17：`allZeroPcm` 把「`read()==0` 补的静音」与「`read()>0` 但整帧全 0」
     * 混成一个数，79.4% 的窗口无法定性。这里再拆 `readZeroPad` / `realZero` /
     * `readBytes` 三个独立计数，`allZeroPcm` 语义保持不变以兼容既有脚本。
     */
    private val txDiag = AudioTxDiag()

    /** 已交给 [Sink.onFrame] 的帧数（不含 CONFIG）。S6 的「线性增长」判据用它。 */
    val frameCount: Long get() = frames.get()

    /** 静音期补出的帧数。判据见 #13 R10 / F2。 */
    val silenceFrameCount: Long get() = silenceFrames.get()

    /** 因编码器输入缓冲不可用而丢弃的 PCM 帧数（正常应为 0）。 */
    val droppedInputCount: Long get() = droppedInputs.get()

    /**
     * 启动失败的原因（成功为 null）。留给 S3/S4 决定手机侧能力位该怎么回传 ——
     * 车机不许猜，能力位只能由采集端给出。
     */
    @Volatile
    var startFailure: String? = null
        private set

    /**
     * 试启动。[AudioRecord] 或 [MediaCodec] 任一初始化失败都只记日志并返回 `false`
     * —— 静默降级，**绝不抛给调用方**（R6）。
     */
    fun start(): Boolean {
        if (running.get()) return true
        startFailure = null

        val rec = try {
            createRecord()
        } catch (e: Exception) {
            fail("AudioRecord init failed: ${e.describe()}")
            null
        }
        if (rec == null) {
            if (startFailure == null) fail("AudioRecord unavailable")
            return false
        }

        val enc = try {
            createEncoder()
        } catch (e: Exception) {
            fail("AAC encoder init failed: ${e.describe()}")
            rec.safeRelease()
            null
        }
        if (enc == null) {
            if (startFailure == null) fail("no usable AAC encoder")
            rec.safeRelease()
            return false
        }

        // AudioRecord 建出来还不产数据：必须先 startRecording()，否则 read() 要么恒返
        // 0（只出静音帧却让 frameCount 照涨，伪装成功）要么返 ERROR_INVALID_OPERATION(-3)
        // 让采集线程首轮即死。这里任何失败都走同一套收敛（safeRelease + fail + false），
        // 绝不抛给调用方（R6）。
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val state = rec.recordingState
                rec.safeRelease()
                enc.safeRelease()
                fail("AudioRecord startRecording failed (state=$state)")
                return false
            }
        } catch (e: Exception) {
            rec.safeRelease()
            enc.safeRelease()
            fail("AudioRecord startRecording failed: ${e.describe()}")
            return false
        }

        record = rec
        codec = enc
        running.set(true)
        thread = daemonThread("AudioCapture", start = true) {
            captureLoop(rec, enc)
        }
        log(
            "Audio: capture started (${spec.sampleRate}Hz/${spec.channelCount}ch/" +
                "${spec.bitrate}bps, ${spec.frameBytes}B/frame, " +
                "%.3ffps)".format(spec.framesPerSecond)
        )
        return true
    }

    override fun close() {
        running.set(false)
        val t = thread
        thread = null
        if (t != null) {
            runCatching { t.interrupt() }
            // 有界 join：与 VideoFrameSender.CLOSE_JOIN_TIMEOUT_MS 同一预算口径，必须
            // 小于 PipelineServer.cleanup() 给管线线程的 2s。
            runCatching { t.join(CLOSE_JOIN_TIMEOUT_MS) }
            // issue #165：join 超时后下面仍会释放 AudioRecord/MediaCodec —— 若采集线程还活着，
            // 那就是明确的 use-after-release。至少把这一个事实落到日志里（FORTIFY 现场只有
            // 一行地址，没有调用栈，超时告警是唯一可对账的时间线）。
            if (t.isAlive) {
                logErr("Audio: capture thread still alive after ${CLOSE_JOIN_TIMEOUT_MS}ms — releasing AudioRecord/encoder anyway")
            }
        }
        record?.safeRelease()
        record = null
        // safeRelease 内部已经 runCatching { stop() }，无需再补一次 stop()。
        codec?.safeRelease()
        codec = null
        // issue #194：累计口径追加在右括号**之后**，避免打断 S23 场景在
        // `scripts/scenarios/s23-audio-stream.sh:63` 用的 `Audio: capture stopped \(frames=…, silence=…\)` 正则。
        log(
            audioTxStoppedLine(
                frames = frames.get(),
                silence = silenceFrames.get(),
                allZeroPcm = txDiag.allZeroPcmTotal,
                readZeroPad = txDiag.readZeroPadTotal,
                realZero = txDiag.realZeroTotal,
                readBytes = txDiag.readBytesTotal,
            )
        )
    }

    private fun fail(reason: String) {
        startFailure = reason
        logErr("Audio: $reason")
    }

    // ── 初始化 ──

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val channelMask = if (spec.channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBytes = AudioRecord.getMinBufferSize(
            spec.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            logErr("Audio: getMinBufferSize returned $minBytes")
            return null
        }
        // 采集缓冲至少装 4 帧，给 ROM 调度抖动留余量（读取走非阻塞，缓冲大小只影响
        // 「一次能取多少」，不影响端到端延迟）。
        val bufferBytes = maxOf(minBytes, spec.frameBytes * RECORD_BUFFER_FRAMES)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(spec.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            logErr("Audio: AudioRecord state=${rec.state} (REMOTE_SUBMIX unavailable)")
            rec.safeRelease()
            return null
        }
        log("Audio: AudioRecord ready (minBuffer=$minBytes, buffer=$bufferBytes)")
        return rec
    }

    /**
     * 逐个真尝试 AAC 编码器，直到有一个能 `configure` + `start`。
     * 委托给 [AudioEncoderSelector]。
     */
    private fun createEncoder(): MediaCodec? =
        AudioEncoderSelector.selectAndCreate(spec, log = log, logErr = logErr)

    // ── 主循环 ──

    private fun captureLoop(rec: AudioRecord, enc: MediaCodec) {
        val pacing = AudioPacing(spec.frameDurationNanos)
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(spec.frameBytes)
        val acc = ByteArray(spec.frameBytes)
        val silence = silenceFrame(spec)
        var accLen = 0
        var frameIndex = 0L

        try {
            while (shouldContinueCapture(sessionRunning(), running.get(), sink.isFailed())) {
                val read = rec.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
                var didWork = false

                when {
                    read > 0 -> {
                        // issue #194：readBytes 是「本窗口实际读到的字节数」——它是
                        // 「读不到」与「读到 0」的分水岭，按读取时刻归属窗口。
                        txDiag.onRead(read)
                        // 凑满一整帧再喂：非阻塞读可能只给半帧。
                        var offset = 0
                        while (offset < read) {
                            val take = minOf(spec.frameBytes - accLen, read - offset)
                            System.arraycopy(chunk, offset, acc, accLen, take)
                            accLen += take
                            offset += take
                            if (accLen == spec.frameBytes) {
                                queuePcm(enc, acc, accLen, frameIndex, pacingSilence = false)
                                frameIndex++
                                accLen = 0
                                didWork = true
                                pacing.onRealData(clockNanos())
                            }
                        }
                    }

                    read == 0 -> {
                        // 无数据（所有 player 消失）：按 21.333 ms 节拍补静音帧（R10）。
                        val due = pacing.silenceFramesDue(clockNanos())
                        repeat(due) {
                            queuePcm(enc, silence, silence.size, frameIndex, pacingSilence = true)
                            frameIndex++
                            silenceFrames.incrementAndGet()
                            didWork = true
                        }
                    }

                    else -> {
                        // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                        logErr("Audio: record.read returned $read, stopping capture")
                        return
                    }
                }

                if (drainOutput(enc, info)) didWork = true

                // 一轮什么都没做就别空转烧 CPU：静音期非阻塞读会立刻返回 0。
                if (!didWork) LockSupport.parkNanos(IDLE_SLEEP_NANOS)
            }
            // issue #165：写端判死后主动停采集，现场能直接看到「是谁先停的」。
            if (sink.isFailed()) {
                logErr("Audio: capture stopped on sink failure (frames=$frameIndex)")
            }
        } catch (e: Exception) {
            logErr("Audio: capture loop died: ${e.describe()}")
        } catch (e: Throwable) {
            // OutOfMemoryError / 编码器 native 崩坏：记一行就走，绝不穿透到会话层。
            logErr("Audio: capture loop fatal: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 把一整帧 PCM 喂给编码器；输入缓冲暂时不可用时计数并丢这一帧。
     *
     * [pacingSilence] 为 true 表示这一帧是 `read()==0` 时 pacing 补的静音帧，
     * 用于把诊断里的 `readZeroPad` 与 `realZero` 分开（issue #194）。
     */
    private fun queuePcm(
        enc: MediaCodec,
        pcm: ByteArray,
        len: Int,
        frameIndex: Long,
        pacingSilence: Boolean
    ) {
        val inputId = try {
            enc.dequeueInputBuffer(0)
        } catch (e: Exception) {
            logErr("Audio: dequeueInputBuffer failed: ${e.describe()}")
            return
        }
        if (inputId < 0) {
            droppedInputs.incrementAndGet()
            return
        }
        val buf: ByteBuffer = try {
            enc.getInputBuffer(inputId) ?: run {
                droppedInputs.incrementAndGet()
                return
            }
        } catch (e: Exception) {
            logErr("Audio: getInputBuffer failed: ${e.describe()}")
            return
        }
        buf.clear()
        // 长度由调用方保证（恒等于 frameBytes），但仍然包住：写缓冲失败若穿出去会杀掉
        // 采集线程，而且这一格 input 槽会永远拿不回来。
        try {
            buf.put(pcm, 0, len)
        } catch (e: Exception) {
            logErr("Audio: filling input buffer failed: ${e.describe()}")
            // 交回一个空缓冲把槽位还给编码器，否则每失败一次就少一格。
            runCatching { enc.queueInputBuffer(inputId, 0, 0, ptsUsForFrame(frameIndex, spec), 0) }
            droppedInputs.incrementAndGet()
            return
        }
        try {
            enc.queueInputBuffer(inputId, 0, len, ptsUsForFrame(frameIndex, spec), 0)
            // issue #194：只统计真正喂进编码器的 PCM，保证分母与编码输出一一对应。
            // 补静音帧与真实帧分开计：前者说明 read() 拿不到数据，后者说明采集被清零。
            if (pacingSilence) {
                txDiag.onPaddedSilence(pcm, len)
            } else {
                txDiag.onRealPcm(pcm, len)
            }
        } catch (e: Exception) {
            logErr("Audio: queueInputBuffer failed: ${e.describe()}")
        }
    }

    /**
     * 排空编码器输出。返回是否处理过任何东西（供调用方判断要不要睡一下）。
     *
     * `csd-0` 与「待发 CONFIG」的状态必须跨轮保存，否则 `INFO_OUTPUT_FORMAT_CHANGED`
     * 之后每一轮都会丢掉 `csd`、永远发不出 CONFIG。
     */
    private fun drainOutput(enc: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        var handled = false
        while (true) {
            val outputId = try {
                enc.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                logErr("Audio: dequeueOutputBuffer failed: ${e.describe()}")
                return handled
            }
            when {
                outputId == MediaCodec.INFO_TRY_AGAIN_LATER -> return handled

                outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    csd0 = readCsd0(enc)
                    // 每次格式变化都要重发 CONFIG（编码器重配置后解码器需重新初始化）。
                    csdDirty = true
                    handled = true
                }

                outputId >= 0 -> {
                    handled = true
                    emitFrame(enc, outputId, info)
                }
            }
        }
    }

    private fun emitFrame(enc: MediaCodec, outputId: Int, info: MediaCodec.BufferInfo) {
        val outBuf = try {
            enc.getOutputBuffer(outputId)
        } catch (e: Exception) {
            logErr("Audio: getOutputBuffer failed: ${e.describe()}")
            null
        }
        try {
            if (outBuf == null || info.size <= 0) return

            if (csdDirty) {
                val c = csd0
                if (c == null) {
                    // 没有 csd-0 就无法让对端解码器初始化；不发半成品，避免车机一直报错。
                    if (!warnedMissingCsd) {
                        warnedMissingCsd = true
                        logErr("Audio: frames arriving without csd-0, dropping until format change")
                    }
                    return
                }
                sink.onConfig(
                    AudioPayload.Config(
                        sampleRate = spec.sampleRate,
                        channelCount = spec.channelCount,
                        bitrate = spec.bitrate,
                        csd = c
                    )
                )
                csdDirty = false
                log("Audio: CONFIG sent (csd-0=${c.size}B)")
            }

            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            val data = ByteArray(info.size)
            outBuf.get(data)
            sink.onFrame(AudioPayload.Frame(ptsMs = info.presentationTimeUs / 1000L, data = data))
            frames.incrementAndGet()
            // issue #194：窗口满 100 帧才返回一行，≈2 s 一条，不会刷屏。
            txDiag.onEncoded(data.size)?.let(log)
        } finally {
            runCatching { enc.releaseOutputBuffer(outputId, false) }
        }
    }

    /** `INFO_OUTPUT_FORMAT_CHANGED` 之后才能读到 `csd-0`（AAC 实测 2 B）。 */
    private fun readCsd0(enc: MediaCodec): ByteArray? = try {
        val buf = enc.outputFormat.getByteBuffer("csd-0")
        if (buf == null) {
            logErr("Audio: output format has no csd-0")
            null
        } else {
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
    } catch (e: Exception) {
        logErr("Audio: reading csd-0 failed: ${e.describe()}")
        null
    }

    companion object {
        /** 采集缓冲至少 4 帧。 */
        private const val RECORD_BUFFER_FRAMES = 4

        /** 空闲时的睡眠粒度；静音期非阻塞读会立刻返回 0，不睡就是空转。 */
        private const val IDLE_SLEEP_NANOS = 1_000_000L

        /** close() 等采集线程退出的上限，对齐 `VideoFrameSender.CLOSE_JOIN_TIMEOUT_MS`。 */
        private const val CLOSE_JOIN_TIMEOUT_MS = 1500L
    }
}

/**
 * issue #165：音频采集的投喂闸门（纯函数，单测见 `AudioPipelineTest`）。
 *
 * 三个来源任一为 false 都必须停止向编码器投喂：
 * - [sessionRunning]：会话整体是否还在跑。`PipelineServer.cleanup()` 第一行置 false，
 *   采集线程不必等到 cleanup 末尾的 `AudioPipeline.close()` 才停 —— 中间那几秒的
 *   shell 恢复步骤正是 `Pipeline exited` 之后仍出现 `queueInputBuffer failed` 的窗口。
 * - [pipelineRunning]：`AudioPipeline.close()` 已请求停止。
 * - [sinkFailed]：9640 写线程判死（`AudioSend: write failed, audio disabled`）。
 *   接收端已经没了，继续编码既无意义，也只会在收尾期制造 CodecException 噪声。
 *
 * 抽成纯函数是为了让「收尾 / 写端失败必须停采集」这条契约能在纯 JVM 上被单测钉住，
 * 而不必依赖真机 REMOTE_SUBMIX + MediaCodec。
 */
internal fun shouldContinueCapture(
    sessionRunning: Boolean,
    pipelineRunning: Boolean,
    sinkFailed: Boolean
): Boolean = sessionRunning && pipelineRunning && !sinkFailed

/** issue #194：发送侧诊断窗口（帧）。46.875 fps ⇒ 100 帧约 2.1 s 一行。 */
internal const val AUDIO_DIAG_TX_WINDOW_FRAMES = 100

/**
 * 判定一段 PCM（只看前 [len] 字节）是否整块全 0（issue #194）。
 *
 * 与 `AudioPlayerPolicy.isPcmSilent` 同一口径，但**允许指定长度**：采集侧凑帧时
 * `acc` 是整帧数组，实际有效长度由 `accLen` 给出，不能把尾巴上的残留算进来。
 */
internal fun isPcmAllZero(pcm: ByteArray, len: Int = pcm.size): Boolean {
    val end = len.coerceAtMost(pcm.size)
    for (i in 0 until end) {
        if (pcm[i].toInt() != 0) return false
    }
    return true
}

/**
 * issue #194 发送侧诊断统计（纯 JVM 可测）。
 *
 * 现场三种可能里，发送侧只能回答第一种：**编码前的 PCM 是不是本来就全 0**。
 *
 * 2026-11-17 实车 79.4% 的窗口 `allZeroPcm` 接近满窗，但 `allZeroPcm` 把两类完全不同的
 * 情况混在一起，无法定性：
 * 1. [onPaddedSilence]：`AudioRecord.read() == 0`，pacing 主动补的静音帧
 *    （采集侧拿不到数据，见 `AudioPipeline.captureLoop` 的 `read == 0` 分支）；
 * 2. [onRealPcm]：`read() > 0` 确实读回了数据，但整帧全 0（采集被 ROM 静音/清空）。
 *
 * 因此拆成三个**独立**计数：
 * - `readZeroPad`：补静音帧数；
 * - `realZero`：真实读回但整帧全零的帧数；
 * - `readBytes`：本窗口从 [AudioRecord.read] 实际读到的字节总数（由 [onRead] 累计）。
 *
 * `allZeroPcm` 语义**保持不变**（仍是「喂进编码器的全零帧数」），且恒有
 * `allZeroPcm == readZeroPad + realZero`，旧日志与脚本口径不受影响。
 *
 * - [onEncoded]：每帧编码输出后调用，记单帧字节数的 min/avg/max；
 * - 编码输出累计满 [windowFrames] 帧时返回一行 `AudioDiag tx ...`（**窗口值**，
 *   便于直接算比例），然后窗口计数归零；
 * - `*Total` 跨窗口累计，供 `close()` 收尾行使用。
 *
 * 为什么用编码输出帧数做窗口边界：编码器一进一出，而 PCM 可能因输入缓冲不可用被丢，
 * 用输出侧计数才能保证「每个窗口都能打出一行」。
 */
internal class AudioTxDiag(
    private val windowFrames: Int = AUDIO_DIAG_TX_WINDOW_FRAMES,
) {
    private var windowAllZero = 0L
    private var windowReadZeroPad = 0L
    private var windowRealZero = 0L
    private var windowReadBytes = 0L
    private var windowEncoded = 0L
    private var windowEncMin = Int.MAX_VALUE
    private var windowEncMax = 0
    private var windowEncSum = 0L

    /** 已交给 [Sink.onFrame] 的编码帧总数（窗口外累计）。 */
    var emittedFrames = 0L
        private set

    /**
     * 跨窗口累计值（`close()` 收尾行用）。
     *
     * `close()` 可能在采集线程 join 超时后仍读取（`AudioPipeline.close()` 的 1.5 s 有界
     * join），所以这里用 AtomicLong，保证读到的是一个完整累计值。
     */
    private val allZeroPcmTotalValue = AtomicLong(0)
    private val readZeroPadTotalValue = AtomicLong(0)
    private val realZeroTotalValue = AtomicLong(0)
    private val readBytesTotalValue = AtomicLong(0)

    /** 编码前全零 PCM 的累计帧数（跨窗口）。 */
    val allZeroPcmTotal: Long get() = allZeroPcmTotalValue.get()

    /** `read()==0` 补静音帧的累计数（跨窗口）。 */
    val readZeroPadTotal: Long get() = readZeroPadTotalValue.get()

    /** `read()>0` 但整帧全零的累计帧数（跨窗口）。 */
    val realZeroTotal: Long get() = realZeroTotalValue.get()

    /** 从 `AudioRecord` 实际读到的累计字节数（跨窗口）。 */
    val readBytesTotal: Long get() = readBytesTotalValue.get()

    /** 记录一次 `read() > 0` 实际读到的字节数（窗口内水位）。 */
    fun onRead(bytes: Int) {
        if (bytes > 0) {
            windowReadBytes += bytes
            readBytesTotalValue.addAndGet(bytes.toLong())
        }
    }

    /** 记录一帧真正读回并喂进编码器的 PCM。 */
    fun onRealPcm(pcm: ByteArray, len: Int = pcm.size) {
        if (isPcmAllZero(pcm, len)) {
            windowAllZero++
            windowRealZero++
            allZeroPcmTotalValue.incrementAndGet()
            realZeroTotalValue.incrementAndGet()
        }
    }

    /**
     * 记录一帧 `read()==0` 时 pacing 补的静音帧。
     *
     * `allZeroPcm` 仍走 `isPcmAllZero` 判定，保持与拆分前逐帧同口径（补静音帧恒为全零，
     * 两者结果一致，但不用假设）。
     */
    fun onPaddedSilence(pcm: ByteArray, len: Int = pcm.size) {
        windowReadZeroPad++
        readZeroPadTotalValue.incrementAndGet()
        if (isPcmAllZero(pcm, len)) {
            windowAllZero++
            allZeroPcmTotalValue.incrementAndGet()
        }
    }

    /**
     * 记录一帧编码输出；窗口满时返回日志行，否则返回 null。
     */
    fun onEncoded(size: Int): String? {
        emittedFrames++
        windowEncoded++
        if (size > 0) {
            if (size < windowEncMin) windowEncMin = size
            if (size > windowEncMax) windowEncMax = size
            windowEncSum += size
        }
        if (windowEncoded < windowFrames) return null
        val line = audioTxDiagWindowLine(
            frames = windowEncoded,
            allZeroPcm = windowAllZero,
            encMin = if (windowEncMin == Int.MAX_VALUE) 0 else windowEncMin,
            encAvg = if (windowEncoded > 0) windowEncSum / windowEncoded else 0L,
            encMax = windowEncMax,
            readZeroPad = windowReadZeroPad,
            realZero = windowRealZero,
            readBytes = windowReadBytes,
        )
        resetWindow()
        return line
    }

    private fun resetWindow() {
        windowAllZero = 0
        windowReadZeroPad = 0
        windowRealZero = 0
        windowReadBytes = 0
        windowEncoded = 0
        windowEncMin = Int.MAX_VALUE
        windowEncMax = 0
        windowEncSum = 0
    }
}

/**
 * `AudioDiag tx` 窗口行（issue #194）。
 *
 * 旧字段 `frames= allZeroPcm= encMin= encAvg= encMax=` 的**位置与顺序不变**，
 * 新字段 `readZeroPad= realZero= readBytes=` 一律追加在行尾 —— 既有按 `encMax=` 结尾
 * 或按固定前缀解析的脚本不会被打断。
 */
internal fun audioTxDiagWindowLine(
    frames: Long,
    allZeroPcm: Long,
    encMin: Int,
    encAvg: Long,
    encMax: Int,
    readZeroPad: Long,
    realZero: Long,
    readBytes: Long,
): String = "AudioDiag tx frames=$frames allZeroPcm=$allZeroPcm " +
    "encMin=$encMin encAvg=$encAvg encMax=$encMax " +
    "readZeroPad=$readZeroPad realZero=$realZero readBytes=$readBytes"

/**
 * `Audio: capture stopped (...)` 收尾行（issue #194）。
 *
 * `(frames=…, silence=…)` 的形态与顺序保持原样（`scripts/scenarios/s23-audio-stream.sh:63`
 * 的正则依赖它），累计口径全部追加在右括号之后。
 */
internal fun audioTxStoppedLine(
    frames: Long,
    silence: Long,
    allZeroPcm: Long,
    readZeroPad: Long,
    realZero: Long,
    readBytes: Long,
): String = "Audio: capture stopped (frames=$frames, silence=$silence) " +
    "allZeroPcm=$allZeroPcm readZeroPad=$readZeroPad realZero=$realZero readBytes=$readBytes"

/** 释放 `AudioRecord`，任何异常都不穿透。 */
private fun AudioRecord.safeRelease() {
    runCatching { stop() }
    runCatching { release() }
}
