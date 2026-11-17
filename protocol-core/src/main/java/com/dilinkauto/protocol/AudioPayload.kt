package com.dilinkauto.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频通道（[Channel.AUDIO]）的载荷编解码，纯 JVM，供 vd-server / 车机 / 桌面端共用。
 *
 * 传输层仍是 [FrameCodec] 的 6 字节帧头；本文件只负责 `AudioMsg.CONFIG` /
 * `AudioMsg.FRAME` 两种消息类型的 **payload**，不碰 socket、不碰时间戳语义。
 *
 * 布局（大端）：
 * - CONFIG：codec(1B) + sampleRate(4B) + channels(1B) + bitrate(4B) + csdLen(1B) + csd(N B)
 *   `csd` 是 MediaCodec 输出的 `csd-0`（AAC-LC 实测 2 字节），必须**先于**任何
 *   FRAME 发送；裸 AAC 帧不含 ADTS，解码器只能靠它初始化（#13 双机实测）。
 * - FRAME：PTS(4B, ms，无符号) + 裸 AAC 帧。AAC-LC 48kHz 每帧 1024 samples，
 *   即 21.333 ms / 46.875 帧每秒；PTS 只用于日志与欠载统计，不做 A/V 对齐。
 */
object AudioPayload {

    const val CODEC_AAC_LC: Byte = 1

    /** CONFIG 固定字段长度，不含 csd。 */
    private const val CONFIG_FIXED_SIZE = 1 + 4 + 1 + 4 + 1
    private const val FRAME_HEADER_SIZE = 4

    data class Config(
        val codec: Byte = CODEC_AAC_LC,
        val sampleRate: Int,
        val channelCount: Int,
        val bitrate: Int,
        val csd: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Config) return false
            return codec == other.codec &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                bitrate == other.bitrate &&
                csd.contentEquals(other.csd)
        }

        override fun hashCode(): Int {
            var result = codec.toInt()
            result = 31 * result + sampleRate
            result = 31 * result + channelCount
            result = 31 * result + bitrate
            result = 31 * result + csd.contentHashCode()
            return result
        }
    }

    data class Frame(
        /** 毫秒时间戳，按无符号 32 位在线上传输，允许回绕。 */
        val ptsMs: Long,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return ptsMs == other.ptsMs && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = ptsMs.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    fun encodeConfig(config: Config): ByteArray {
        require(config.codec == CODEC_AAC_LC) { "Unsupported audio codec: ${config.codec}" }
        require(config.sampleRate > 0) { "Invalid sample rate: ${config.sampleRate}" }
        require(config.channelCount in 1..0xFF) { "Invalid channel count: ${config.channelCount}" }
        require(config.bitrate >= 0) { "Invalid bitrate: ${config.bitrate}" }
        require(config.csd.size <= 0xFF) { "Audio csd too large: ${config.csd.size}" }
        val total = CONFIG_FIXED_SIZE + config.csd.size
        require(total <= FrameCodec.MAX_PAYLOAD_SIZE) {
            "Audio CONFIG too large: $total > ${FrameCodec.MAX_PAYLOAD_SIZE}"
        }
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            put(config.codec)
            putInt(config.sampleRate)
            put(config.channelCount.toByte())
            putInt(config.bitrate)
            put(config.csd.size.toByte())
            put(config.csd)
        }.array()
    }

    fun decodeConfig(data: ByteArray): Config {
        if (data.size > FrameCodec.MAX_PAYLOAD_SIZE) {
            throw ProtocolDecodeException("Audio CONFIG too large: ${data.size}")
        }
        if (data.size < CONFIG_FIXED_SIZE) {
            throw ProtocolDecodeException("Audio CONFIG too short: ${data.size}")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val codec = buf.get()
        val sampleRate = buf.getInt()
        val channelCount = buf.get().toInt() and 0xFF
        val bitrate = buf.getInt()
        val csdLen = buf.get().toInt() and 0xFF
        if (buf.remaining() != csdLen) {
            throw ProtocolDecodeException(
                "Audio CONFIG csd length mismatch: declared=$csdLen actual=${buf.remaining()}"
            )
        }
        val csd = ByteArray(csdLen)
        buf.get(csd)
        val config = Config(codec, sampleRate, channelCount, bitrate, csd)
        // 与编码侧校验对称：对端声明了未知 codec 或零采样率时，必须在解码
        // 边界就失败，不能等到 MediaCodec.configure() 里再炸。
        if (config.codec != CODEC_AAC_LC) {
            throw ProtocolDecodeException("Unsupported audio codec: ${config.codec}")
        }
        if (config.sampleRate <= 0) {
            throw ProtocolDecodeException("Invalid sample rate: ${config.sampleRate}")
        }
        if (config.channelCount == 0) {
            throw ProtocolDecodeException("Invalid channel count: ${config.channelCount}")
        }
        if (config.bitrate < 0) {
            throw ProtocolDecodeException("Invalid bitrate: ${config.bitrate}")
        }
        return config
    }

    fun encodeFrame(frame: Frame): ByteArray {
        require(frame.data.isNotEmpty()) { "Audio FRAME payload must not be empty" }
        val total = FRAME_HEADER_SIZE.toLong() + frame.data.size
        require(total <= FrameCodec.MAX_PAYLOAD_SIZE) {
            "Audio FRAME too large: $total > ${FrameCodec.MAX_PAYLOAD_SIZE}"
        }
        val pts = (frame.ptsMs and 0xFFFF_FFFFL).toInt()
        return ByteBuffer.allocate(total.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(pts)
            put(frame.data)
        }.array()
    }

    fun decodeFrame(data: ByteArray): Frame {
        if (data.size > FrameCodec.MAX_PAYLOAD_SIZE) {
            throw ProtocolDecodeException("Audio FRAME too large: ${data.size}")
        }
        if (data.size <= FRAME_HEADER_SIZE) {
            throw ProtocolDecodeException("Audio FRAME too short: ${data.size}")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val ptsMs = buf.int.toLong() and 0xFFFF_FFFFL
        val payload = ByteArray(buf.remaining())
        buf.get(payload)
        return Frame(ptsMs, payload)
    }
}
