package com.dilinkauto.server.decoder

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
 * 音频播放的周期/终局日志行（契约格式，S6 直接 `grep -oE` / `grep -F`，Refs #73, #79, #84, #164）：
 *
 * ```
 * [AudioPlayer] decoded=<累计解码帧> starved=<累计欠载补静音> underrun=<AudioTrack underrunCount> maxRecoverMs=<最长恢复耗时> queue=<队列深度> silence=<累计静音帧> padIdle=<累计空闲补帧>
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
 */
internal fun audioPlayerStatLine(
    decoded: Long,
    starved: Long,
    underrun: Int,
    maxRecoverMs: Long,
    queue: Int,
    silence: Long = 0L,
    padIdle: Long = 0L,
): String =
    "[AudioPlayer] decoded=$decoded starved=$starved underrun=$underrun " +
        "maxRecoverMs=$maxRecoverMs queue=$queue silence=$silence padIdle=$padIdle"

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
