package com.dilinkauto.vdserver

/**
 * 音频采集与编码规格（issue #13 方案 A / 分节 S2）。
 *
 * 参数取自 #13 的双机实测：AAC-LC / 48 kHz / 立体声 / 128 kbps。48 kHz 下每帧
 * 1024 samples，即 **21.333 ms / 46.875 fps** —— 这个节拍是硬约束，不是随手挑的
 * 数字：补帧若按 20 ms 走，每秒会多发 7~8 帧，播放端队列从 5 涨到 13~15
 * （约 +300 ms 延迟，见 #13 §2.5 F2）。
 *
 * 派生量都从 [samplesPerFrame] 与 [sampleRate] 算出来，避免多处硬编码互相漂移。
 */
internal data class AudioSpec(
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val bitrate: Int = BITRATE,
    val samplesPerFrame: Int = SAMPLES_PER_FRAME
) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0: $sampleRate" }
        require(channelCount in 1..MAX_CHANNELS) {
            "channelCount must be in 1..$MAX_CHANNELS: $channelCount"
        }
        require(bitrate > 0) { "bitrate must be > 0: $bitrate" }
        require(samplesPerFrame > 0) { "samplesPerFrame must be > 0: $samplesPerFrame" }
    }

    /** 一帧的 PCM 字节数（16-bit）。48 kHz 立体声 → 4096 B。 */
    val frameBytes: Int get() = samplesPerFrame * channelCount * BYTES_PER_SAMPLE

    /** 一帧的时长（纳秒）。1024 samples @ 48 kHz → 21_333_333 ns。 */
    val frameDurationNanos: Long get() =
        samplesPerFrame.toLong() * 1_000_000_000L / sampleRate

    /** 帧率。1024 samples @ 48 kHz → 46.875。 */
    val framesPerSecond: Double get() = sampleRate.toDouble() / samplesPerFrame

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val BITRATE = 128_000
        const val SAMPLES_PER_FRAME = 1024
        const val MAX_CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2

        val DEFAULT = AudioSpec()
    }
}

/**
 * 第 [frameIndex] 帧（从 0 开始）的呈现时间戳，单位微秒 —— 喂给 `MediaCodec`
 * `queueInputBuffer` 用。
 *
 * 用 Long 运算。Int 域下 `frameIndex * samplesPerFrame * 1_000_000` 在第 3 帧
 * （3072 samples × 1e6 = 3.07e9 > 2^31 = 2.147e9）就已经溢出；即便只看 sample 计数，
 * 2^31 samples @ 48 kHz 也只有约 12.4 小时（44 739 s）。两者都说明这里必须是 Long。
 */
internal fun ptsUsForFrame(frameIndex: Long, spec: AudioSpec): Long =
    frameIndex * spec.samplesPerFrame * 1_000_000L / spec.sampleRate

/**
 * 一帧全零 PCM，用于静音期补帧。
 *
 * 不设 RMS 阈值判静音：两台调试机的采集幅度差 5.8 倍（munch peak 2040 / MI 9 peak
 * 351，#13 R12），绝对幅度不可移植；这里只区分「有帧 / 无帧」。
 */
internal fun silenceFrame(spec: AudioSpec): ByteArray = ByteArray(spec.frameBytes)
