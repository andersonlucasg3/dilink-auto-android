package com.dilinkauto.server.decoder

import com.dilinkauto.protocol.AudioPayload
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 车机端音频播放的纯判定/格式化（issue #73 分节 PB / Refs #13）。
 *
 * 与 `service/AudioPolicy.kt` 同一范式：`app-server` 没有 Robolectric
 * （`build.gradle.kts` 的 `unitTests.isReturnDefaultValues = true` 让 `AudioTrack` /
 * `MediaCodec` 这些框架类拿不到真值），凡是不碰 Android API 的判定与字符串组装都
 * 挤到 top-level `internal fun` 里，由 `AudioPlayerPolicyTest` 用字面量钉住。
 * （连接门判定 [carShouldConnectAudio] 与它同源，但落 `service/AudioPolicy.kt`
 * —— 那是「车机策略」的既定归口。）
 */

/**
 * 音频播放的周期/终局日志行（契约格式，S6 直接 `grep -oE` / `grep -F`，Refs #73, #79, #84, #164, #194）：
 *
 * ```
 * [AudioPlayer] decoded=<累计解码帧> starved=<累计欠载补静音> underrun=<AudioTrack underrunCount> maxRecoverMs=<最长恢复耗时> queue=<队列深度> silence=<累计静音帧> padIdle=<累计空闲补帧> inZero=<累计输入全零帧> inMin=<单帧 payload 最小字节> inMax=<单帧 payload 最大字节> outRmsMax=<本窗口输出最大 RMS>
 * ```
 *
 * 与 `VideoDecoder` 的 `Fed ... drops=...` 汇总行同风格：字段名 `=` 分隔、空格相连，
 * 便于 `grep -F` 精确命中与人工阅读。前缀 `[AudioPlayer]` 与 `VideoDecoder` 的
 * `[VideoDecoder]` 对齐 —— 都经 `carLogSend`（tag `CarLog`）中继进手机侧 `client.log`。
 *
 * 组装成纯函数是为了：格式一旦漂移，`AudioPlayerPolicyTest` 会红；而行本身在真机
 * 上的可达性由 S6 场景断言（`scripts/lib/asserts.sh` 的 `countF`）。
 *
 * `padIdle` 是 issue #164 新增的**归因字段**，与 `starved` 严格区分：
 * - `starved`：输入队列空（`poll()` 返回 null），没数据可解；
 * - `padIdle`：**有**输入帧，但解码器本拍没吐出 PCM（`INFO_TRY_AGAIN_LATER`），
 *   为了让 `AudioTrack` 不见底而补的静音帧。
 *
 * 两者都涨 → 网络/解码持续供不上；只有 `padIdle` 涨 → 解码器输出突发、节拍对不齐
 * （issue #164 的原始形态）；两个都不涨而 `underrun` 仍涨 → 帧写进去了却没播出去
 * （设备/路由问题，属候选 C 的范畴）。
 *
 * issue #194 新增的四个字段用于区分「传输清零 / 解码器吐 0 / 采集侧就是静音」：
 * - `inZero`：收到的 payload 中整块全 0 的累计帧数（与 `silence`/`outZero` 分开计数）；
 * - `inMin`/`inMax`：单帧 payload 字节数的会话范围（帧边界异常时会偏离几百字节）；
 * - `outRmsMax`：**本窗口**（2.5 s）解码输出的最大 RMS —— 用来识别「有内容但极轻」。
 */
internal fun audioPlayerStatLine(
    decoded: Long,
    starved: Long,
    underrun: Int,
    maxRecoverMs: Long,
    queue: Int,
    silence: Long = 0L,
    padIdle: Long = 0L,
    inZero: Long = 0L,
    inMin: Int = 0,
    inMax: Int = 0,
    outRmsMax: Int = 0,
): String =
    "[AudioPlayer] decoded=$decoded starved=$starved underrun=$underrun " +
        "maxRecoverMs=$maxRecoverMs queue=$queue silence=$silence padIdle=$padIdle " +
        "inZero=$inZero inMin=$inMin inMax=$inMax outRmsMax=$outRmsMax"

/**
 * issue #194 的独立诊断行，与 [audioPlayerStatLine] 同一节奏（2.5 s）但只放音频口径，
 * 方便现场 `grep AudioDiag` 一把抓出发送侧（`AudioDiag tx`）与接收侧（`AudioDiag rx`）。
 *
 * 字段全部是**累计值**：多行之间可直接做差得到窗口增量。
 */
internal fun audioRxDiagLine(
    inFrames: Long,
    inZero: Long,
    inMin: Int,
    inMax: Int,
    outZero: Long,
    outRmsMax: Int,
): String =
    "AudioDiag rx inFrames=$inFrames inZero=$inZero inMin=$inMin inMax=$inMax " +
        "outZero=$outZero outRmsMax=$outRmsMax"

/**
 * `AudioTrack` 的低水位（帧，issue #164）。
 *
 * 3 帧 = 3 × 21.333 ms = **64 ms**，取值依据：主循环按 [AudioPlayer] 的 21.333 ms 节拍
 * 推进，实测解码器会连续 2 拍返回 `INFO_TRY_AGAIN_LATER`（42.7 ms）再一次性补 2 帧，
 * 再叠加 `parkNanos` 的数毫秒抖动 —— 低水位低于 3 帧时水位会摸到 0，就是实车
 * 22.6 次/秒 `underrun` 的直接来源。
 *
 * 为什么不用更高：补帧只会把水位顶到这里，水位越高 → 常驻延迟越大。3 帧既是
 * 「够覆盖两拍空输出」的最小值，也把新增的端到端延迟钉在 64 ms，不随会话时长累积。
 */
internal const val AUDIO_TRACK_LOW_WATER_FRAMES = 3

/**
 * `AudioTrack` 里「已写入但还没播出去」的帧数（issue #164）。
 *
 * `writtenFrames` 是本进程累计写进 track 的帧数，`playbackHeadFrames` 是
 * `AudioTrack.getPlaybackHeadPosition()`（硬件播放头，单位是**帧**，即每声道采样数）。
 * 两者之差就是缓冲里积压的量。
 *
 * 两类异常都收敛到边界而不抛：播放头超前（track 被 flush/重建，或 Int 回绕算错）钳到 0，
 * 差值超过容量（ROM 分配的缓冲比请求的大）钳到 [bufferFrames] —— 后者让调用方
 * 走「缓冲已满、不补」分支，宁可少补也不堆积。
 */
internal fun audioTrackPendingFrames(
    writtenFrames: Long,
    playbackHeadFrames: Long,
    bufferFrames: Int,
): Int {
    if (bufferFrames <= 0) return 0
    return (writtenFrames - playbackHeadFrames)
        .coerceIn(0L, bufferFrames.toLong())
        .toInt()
}

/**
 * 本拍该补多少帧静音（issue #164 候选 B 的判定核心，纯函数）。
 *
 * 背景：`AudioPlayer` 的主循环每 21.333 ms 只 poll/feed/drain 一次。输入队列有帧、
 * 但解码器本拍返回 `INFO_TRY_AGAIN_LATER` 时，这一拍**一个 PCM 字节都不写**；
 * 只有「队列空」才补静音（计 `starved`）。于是 4 帧（85 ms）的 track 被一拍一拍啃到 0，
 * 底层按 22.6 次/秒记 underrun，而应用层 `starved` 只有 25。
 *
 * 规则（表驱动钉在 `AudioPlayerPadPolicyTest`）：
 * - [wroteFramesThisTick] > 0：本拍已有真实 PCM → 不补（水位低是解码器供不上的问题，
 *   不该用静音掩盖）；
 * - 水位已到 [lowWaterFrames] → 不补；
 * - 缓冲已满（[pendingFrames] >= [bufferFrames]）→ 不补，**这是防无限堆积的闸门**：
 *   补帧只能把水位顶到低水位，顶不上去就停，所以补出来的延迟有上界、不随会话累积。
 *
 * @return 要补的帧数（0 表示不补），不会超过 `bufferFrames - pendingFrames`。
 */
internal fun audioPadSilenceFrames(
    bufferFrames: Int,
    pendingFrames: Int,
    wroteFramesThisTick: Int,
    lowWaterFrames: Int = AUDIO_TRACK_LOW_WATER_FRAMES,
): Int {
    if (wroteFramesThisTick > 0) return 0
    if (bufferFrames <= 0 || lowWaterFrames <= 0) return 0
    val pending = pendingFrames.coerceIn(0, bufferFrames)
    val target = lowWaterFrames.coerceAtMost(bufferFrames)
    val deficit = target - pending
    if (deficit <= 0) return 0
    return deficit.coerceAtMost(bufferFrames - pending)
}

/**
 * 判定 PCM 16-bit 缓冲是否纯静音（issue #84 / Refs #79）。
 *
 * AAC-LC 解码出来的 PCM 每样本 2 字节（小端 16-bit signed）。
 * 当且仅当字节流全部为 0 时判定为静音（与 vd-server 端 silenceFrame(spec) 全 0 数组对称）。
 * 若缓冲为空，返回 true。
 */
internal fun isPcmSilent(pcm: ByteArray): Boolean {
    for (b in pcm) {
        if (b.toInt() != 0) return false
    }
    return true
}

/**
 * 16-bit **小端** PCM 的 RMS（issue #194）。
 *
 * AAC-LC 解码输出的每个样本是 2 字节小端有符号整数：低字节在前，高字节按符号扩展
 * （`(hi shl 8) or lo`）。用 Long 累加平方和：单帧 4096 B = 2048 样本，最坏
 * 2048 × 32768² ≈ 2.2e12，远小于 `Long.MAX_VALUE`。
 *
 * 末尾落单的字节不构成完整样本，直接忽略（不按 0 计入，否则会把 RMS 拉低）。
 * 返回整数 RMS（0 ~ 32768）：0 表示全静音；「有内容但极轻」也能量出来。
 */
internal fun pcm16Rms(pcm: ByteArray, len: Int = pcm.size): Int {
    val end = len.coerceAtMost(pcm.size)
    val samples = end / 2
    if (samples <= 0) return 0
    var sum = 0L
    var i = 0
    while (i + 1 < end) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = (hi shl 8) or lo
        sum += sample.toLong() * sample
        i += 2
    }
    return kotlin.math.sqrt(sum.toDouble() / samples).toInt()
}

/**
 * issue #194 接收侧音频诊断的计数容器（纯 JVM 可测，不碰任何 Android API）。
 *
 * 三个口径必须**分开**累计，否则又会回到「只看到 99% 静音、不知道是传输还是解码」的
 * 状态：
 * - [inZero]：收到的 payload 整块全 0（含稍后被抖动缓冲丢最旧的帧）—— 对应「传输/打包把 payload 清零」；
 * - [outZero]：解码输出整块全 0（与历史 `silence` 同一口径）—— 对应「解码器吐 0」；
 * - [inFrames]/[outFrames]：两端口径各自的分母，避免拿一个分母去算两个比例。
 *   `inFrames != decoded` 本身就说明有帧在入队/喂解码器环节被丢。
 *
 * [windowRmsMax] 是**窗口**值：由 [consumeWindowRmsMax] 取走并清零，让 2.5 s 统计行
 * 报「本窗口最大 RMS」而不是会话历史最大值；累计字段只由 [reset] 清零（`start()` 时）。
 */
internal class AudioRxDiag {
    private val inFramesValue = AtomicLong(0)
    private val inZeroValue = AtomicLong(0)
    private val outFramesValue = AtomicLong(0)
    private val outZeroValue = AtomicLong(0)

    @Volatile
    private var inMinBytesValue = Int.MAX_VALUE

    @Volatile
    private var inMaxBytesValue = 0

    @Volatile
    private var windowRmsMaxValue = 0

    /** 收到的 payload 累计帧数（通过入队闸门即计入）。 */
    val inFrames: Long get() = inFramesValue.get()

    /** 其中整块全 0 的累计帧数。 */
    val inZero: Long get() = inZeroValue.get()

    /** 单帧 payload 的会话最小字节数（无输入时为 0）。 */
    val inMinBytes: Int get() = if (inMinBytesValue == Int.MAX_VALUE) 0 else inMinBytesValue

    /** 单帧 payload 的会话最大字节数（无输入时为 0）。 */
    val inMaxBytes: Int get() = inMaxBytesValue

    /** 解码输出累计帧数。 */
    val outFrames: Long get() = outFramesValue.get()

    /** 解码输出整块全 0 的累计帧数。 */
    val outZero: Long get() = outZeroValue.get()

    /** 当前窗口的最大 RMS（[consumeWindowRmsMax] 取走后归零）。 */
    val windowRmsMax: Int get() = windowRmsMaxValue

    /** 记录一帧收到的 payload（网络线程调用，计数器线程安全）。 */
    fun onInput(payload: ByteArray) {
        inFramesValue.incrementAndGet()
        if (isPcmSilent(payload)) inZeroValue.incrementAndGet()
        val n = payload.size
        if (n < inMinBytesValue) inMinBytesValue = n
        if (n > inMaxBytesValue) inMaxBytesValue = n
    }

    /** 记录一帧解码输出 PCM。 */
    fun onOutput(pcm: ByteArray) {
        outFramesValue.incrementAndGet()
        if (isPcmSilent(pcm)) outZeroValue.incrementAndGet()
        val rms = pcm16Rms(pcm)
        if (rms > windowRmsMaxValue) windowRmsMaxValue = rms
    }

    /** 取走本窗口最大 RMS 并复位（供 2.5 s 统计行使用）。 */
    fun consumeWindowRmsMax(): Int {
        val v = windowRmsMaxValue
        windowRmsMaxValue = 0
        return v
    }

    /** 新会话开始：全部归零。 */
    fun reset() {
        inFramesValue.set(0)
        inZeroValue.set(0)
        outFramesValue.set(0)
        outZeroValue.set(0)
        inMinBytesValue = Int.MAX_VALUE
        inMaxBytesValue = 0
        windowRmsMaxValue = 0
    }
}

/**
 * 喂帧侧是否该把这一帧交给 jitter 缓冲（issue #181）。
 *
 * 三个闸门任一关闭都必须丢弃：
 * - [running]：播放器未启动 / 已 stop；
 * - [playbackFailed]：播放线程已判死（CONFIG 硬超时、解码器或 `AudioTrack` 创建失败）。
 *   原来这里只判 `running`，于是致命退出后喂帧侧继续填队列、持续刷「Jitter queue full」，
 *   而播放侧早已不存在；
 * - [hasConfig]：裸 AAC 缺 `csd-0` 解不了，CONFIG 之前只能丢（原有语义）。
 *
 * 只决定「是否入队」，**不改**队列满时的丢最旧帧策略（属 #179）。
 */
internal fun audioShouldEnqueueFrame(
    running: Boolean,
    playbackFailed: Boolean,
    hasConfig: Boolean,
): Boolean = running && !playbackFailed && hasConfig

/** 等第一份 CONFIG 的结果（issue #181）。 */
internal sealed class AudioConfigWaitResult {
    /** CONFIG 已到，可以建解码器/`AudioTrack` 起播。 */
    data class Ready(val config: AudioPayload.Config) : AudioConfigWaitResult()

    /** 硬超时：本次会话拿不到 CONFIG，调用方必须显式上报而不是静默退出。 */
    data class TimedOut(val waitedMs: Long) : AudioConfigWaitResult()

    /** `stop()` 已请求停止，属正常收尾，不算故障。 */
    object Stopped : AudioConfigWaitResult()
}

/**
 * 软阈值：到这里只告警「仍在等」，不退出（issue #181 候选 A）。
 *
 * 原实现把它当**硬退出**阈值：现场 CONFIG 只晚 109 ms 就整段静音。原因是这段预算
 * 从车机侧音频 TCP 连上就开始计（`CarSessionCoordinator.connectAudioIfEnabled`），
 * 而手机端 9640 的 accept 被刻意放在视频管线 unpark 之后（`PipelineServer.run`），
 * CONFIG 只能在 accept + 采集 + 首帧编码输出之后才发 —— 覆盖的是 VD/GL bring-up，
 * 与音频首包快慢无关。
 */
internal const val AUDIO_CONFIG_SOFT_TIMEOUT_MS = 3000L

/** 硬阈值：与视频首帧同量级（8~10s），到这里才终结并显式上报。 */
internal const val AUDIO_CONFIG_HARD_TIMEOUT_MS = 10_000L

/** 轮询粒度，与原实现一致（2 ms）。 */
internal const val AUDIO_CONFIG_POLL_NANOS = 2_000_000L

/**
 * 等第一份 CONFIG 的时序机（issue #181，纯 JVM 可测）。
 *
 * 时钟与睡眠都从构造参数注入，单测用假时钟把 3.1 s / 10 s 压成毫秒级，
 * 不必真的起播放线程，也不依赖 `SystemClock`（无 Robolectric 时它只返回默认值）。
 *
 * 规则（表驱动钉在 `AudioConfigWaitTest`）：
 * 1. 每轮先读 CONFIG —— 到达优先于任何超时判定，迟到 109 ms 也能起播；
 * 2. 到 [softTimeoutMs] 只回调一次 [onStillWaiting]（可观测，但不退出）；
 * 3. 到 [hardTimeoutMs] 才回调 [onTimeout] 并返回 [AudioConfigWaitResult.TimedOut]，
 *    调用方必须据此显式上报，不能静默退出；
 * 4. `running` 转 false（`stop()`）立即返回 [AudioConfigWaitResult.Stopped]，不算故障。
 */
internal class AudioConfigWaiter(
    private val config: () -> AudioPayload.Config?,
    private val running: () -> Boolean,
    private val clockMs: () -> Long,
    private val parkNanos: (Long) -> Unit = { LockSupport.parkNanos(it) },
    private val softTimeoutMs: Long = AUDIO_CONFIG_SOFT_TIMEOUT_MS,
    private val hardTimeoutMs: Long = AUDIO_CONFIG_HARD_TIMEOUT_MS,
    private val onStillWaiting: (Long) -> Unit = {},
    private val onTimeout: (Long) -> Unit = {},
) {
    fun await(): AudioConfigWaitResult {
        val startMs = clockMs()
        var warned = false
        while (true) {
            config()?.let { return AudioConfigWaitResult.Ready(it) }
            if (!running()) return AudioConfigWaitResult.Stopped
            val waited = clockMs() - startMs
            if (waited >= hardTimeoutMs) {
                onTimeout(waited)
                return AudioConfigWaitResult.TimedOut(waited)
            }
            if (!warned && waited >= softTimeoutMs) {
                warned = true
                onStillWaiting(waited)
            }
            parkNanos(AUDIO_CONFIG_POLL_NANOS)
        }
    }
}
