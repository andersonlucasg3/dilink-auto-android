package com.dilinkauto.vdserver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.dilinkauto.protocol.VideoConfig
import java.io.IOException

/**
 * AVC (H.264) 编码器过滤探测与选择器。
 */
internal object VideoEncoderSelector {

    data class SelectedEncoder(
        val codec: MediaCodec,
        val surface: Surface,
        val name: String,
        val skipped: List<String>
    )

    /**
     * 枚举框架里所有 video/avc 编码器（名字 + 是否纯软实现），保留框架原顺序。
     *
     * 用 `ALL_CODECS`：全量列表自主排序 + 逐个真尝试。
     */
    fun avcEncoderCandidates(): List<Pair<String, Boolean>> {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val out = ArrayList<Pair<String, Boolean>>(infos.size)
        for (info in infos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            out += info.name to info.isSoftwareOnly
        }
        return out
    }

    /**
     * 按 [orderEncoderCandidates] 排序并逐个尝试创建、配置与启动，返回首个成功者。
     *
     * @param width 编码宽度
     * @param height 编码高度
     * @param bitrate 目标码率
     * @param fps 帧率
     * @param iFrameInterval IDR 关键帧间隔（秒）
     * @param candidates 可选注入候选列表（用于测试），默认枚举当前系统
     * @throws IOException 当所有候选均失败时抛出，聚合所有候选的失败原因
     */
    fun selectAndCreate(
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        iFrameInterval: Int = 1,
        candidates: List<Pair<String, Boolean>> = avcEncoderCandidates()
    ): SelectedEncoder {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_LATENCY, 0)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        // 单位是微秒；与 GlPipeline 的静止保活合成共用一个值（issue #153-PA）。
        format.setLong("repeat-previous-frame-after", VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS * 1000L)

        val tried = mutableListOf<String>()
        for (name in orderEncoderCandidates(candidates)) {
            try {
                val codec = MediaCodec.createByCodecName(name)
                val surface = probeCodec(codec, format)
                return SelectedEncoder(codec, surface, name, tried)
            } catch (e: Exception) {
                // 失败原因保留在聚合信息里；空 message 的 CodecException 用类名兜底。
                val why = e.describe()
                tried += "$name($why)"
            }
        }
        throw IOException("没有可用的 H.264 编码器；候选与失败原因: ${tried.joinToString("; ")}")
    }

    /**
     * 探测并启动单个编码器候选。若 start 或后续步骤失败，必须确保已分配的 Surface 与 Codec 都被释放。
     */
    internal fun probeCodec(
        codec: MediaCodec,
        format: MediaFormat,
        configure: (MediaCodec, MediaFormat) -> Unit = { c, f -> c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) },
        createInputSurface: (MediaCodec) -> Surface = { it.createInputSurface() },
        start: (MediaCodec) -> Unit = { it.start() },
        releaseCodec: (MediaCodec) -> Unit = { it.release() },
        releaseSurface: (Surface) -> Unit = { it.release() }
    ): Surface {
        var surface: Surface? = null
        try {
            configure(codec, format)
            surface = createInputSurface(codec)
            start(codec)
            return surface
        } catch (e: Exception) {
            // 半成品实例必须释放：失败的 codec 已占用底层组件；releasing the codec does not release the Surface
            runCatching { surface?.let { releaseSurface(it) } }
            runCatching { releaseCodec(codec) }
            throw e
        }
    }
}

/**
 * 编码器候选排序（纯函数，单测锁定契约 —— 见 `EncoderCandidateOrderTest`）。
 *
 * 排序键（稳定排序，同键保留框架给出的原顺序）：
 *  1. 非纯软实现优先（`isSoftwareOnly=false`，硬编性能）；
 *  2. 同组内 Codec2（`c2.` 前缀）优先于遗留 OMX（`OMX.` 前缀）—— 新设备上的
 *     OMX 封装常是陈旧/残缺实现。2026-10-10 MI 9 实机：`OMX.qcom.video.encoder.avc`
 *     在 configure 即失败（不支持 COLOR_FormatSurface），`c2.qti.avc.encoder` 正常。
 *
 * 排序只决定"先试谁"，正确性由 [VideoEncoderSelector.selectAndCreate] 的逐个真尝试兜底：
 * 靠前的候选失败会自动落到下一个，直到 `c2.android`（软编）之类的最后防线。
 */
internal fun orderEncoderCandidates(candidates: List<Pair<String, Boolean>>): List<String> =
    candidates
        .sortedWith(compareBy({ it.second }, { if (it.first.startsWith("c2.")) 0 else 1 }))
        .map { it.first }
