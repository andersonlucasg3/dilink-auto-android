package com.dilinkauto.vdserver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * AAC (audio/mp4a-latm) 音频编码器过滤探测与选择器。
 */
internal object AudioEncoderSelector {

    /**
     * 待选编码器信息描述（用于解耦 Android 框架测试）。
     */
    data class CodecDescriptor(
        val name: String,
        val isEncoder: Boolean,
        val isSoftwareOnly: Boolean,
        val supportedTypes: List<String>
    )

    /**
     * 过滤并返回 AAC 编码器候选（保留原顺序）。
     */
    fun filterAacEncoders(descriptors: Iterable<CodecDescriptor>): List<Pair<String, Boolean>> =
        descriptors.filter { desc ->
            desc.isEncoder && desc.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            }
        }.map { it.name to it.isSoftwareOnly }

    /**
     * 枚举框架里所有 audio/mp4a-latm 编码器（名字 + 是否纯软实现），保留框架原顺序。
     */
    fun audioEncoderCandidates(): List<Pair<String, Boolean>> =
        audioEncoderCandidates(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos)

    /**
     * 从给定的 MediaCodecInfo 数组中枚举 AAC 编码器候选。
     */
    fun audioEncoderCandidates(codecInfos: Array<MediaCodecInfo>): List<Pair<String, Boolean>> =
        codecInfos
            .filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
                }
            }
            .map { it.name to it.isSoftwareOnly }

    /**
     * 逐个真尝试 AAC 编码器，直到有一个能 configure + start。
     */
    fun selectAndCreate(
        spec: AudioSpec,
        candidates: List<Pair<String, Boolean>> = audioEncoderCandidates(),
        log: (String) -> Unit = { PipeLog.log(it) },
        logErr: (String) -> Unit = { PipeLog.err(it) }
    ): MediaCodec? {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            // 输入缓冲给两帧的余量，避免部分读场景下编码器频繁要缓冲。
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, spec.frameBytes * 2)
        }
        val tried = mutableListOf<String>()
        for (name in orderEncoderCandidates(candidates)) {
            tried += name
            var candidate: MediaCodec? = null
            try {
                candidate = MediaCodec.createByCodecName(name)
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                candidate.start()
                log("Audio: encoder=$name")
                return candidate
            } catch (e: Exception) {
                logErr("Audio: encoder '$name' unusable: ${e.describe()}")
                candidate?.safeRelease()
                continue
            }
        }
        logErr("Audio: no usable AAC encoder among $tried")
        return null
    }
}

/** 释放 `MediaCodec`，任何异常都不穿透（未 start 过的编解码器 `stop()` 会抛，已被吞掉）。 */
internal fun MediaCodec.safeRelease() {
    runCatching { stop() }
    runCatching { release() }
}
