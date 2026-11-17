package com.dilinkauto.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * issue #67（S1）：9640 音频通道的 payload 契约。
 *
 * 只测纯 JVM 编解码与 [FrameCodec] 帧头集成，不碰 socket / MediaCodec。
 * 参数取自 #13 双机实测：AAC-LC 48 kHz stereo 128 kbps，csd-0 为 2 字节，
 * 每帧 1024 samples（21.333 ms / 46.875 fps，平均 ~341 B）。
 */
class AudioPayloadTest {

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("Expected ${T::class.java.simpleName} to be thrown")
        error("unreachable")
    }

    private fun config(csd: ByteArray = byteArrayOf(0x12, 0x10)) = AudioPayload.Config(
        sampleRate = 48_000,
        channelCount = 2,
        bitrate = 128_000,
        csd = csd
    )

    @Test
    fun `audio port is 9640 and does not collide with existing ports`() {
        assertEquals(9640, Ports.AUDIO_PORT)
        assertNotEquals(Ports.DEFAULT_PORT, Ports.AUDIO_PORT)
        assertNotEquals(Ports.VIDEO_PORT, Ports.AUDIO_PORT)
        assertNotEquals(Ports.INPUT_PORT, Ports.AUDIO_PORT)
    }

    @Test
    fun `config round trip carries csd-0 out of band`() {
        val original = config()
        val encoded = AudioPayload.encodeConfig(original)
        // 1 + 4 + 1 + 4 + 1 个固定字段 + 2 字节 csd
        assertEquals(13, encoded.size)
        val decoded = AudioPayload.decodeConfig(encoded)
        assertEquals(original, decoded)
        assertArrayEquals(byteArrayOf(0x12, 0x10), decoded.csd)
        assertEquals(48_000, decoded.sampleRate)
        assertEquals(2, decoded.channelCount)
        assertEquals(128_000, decoded.bitrate)
        assertEquals(AudioPayload.CODEC_AAC_LC, decoded.codec)
    }

    @Test
    fun `config with empty or maximum csd round trips`() {
        assertEquals(config(ByteArray(0)), AudioPayload.decodeConfig(AudioPayload.encodeConfig(config(ByteArray(0)))))
        val maxCsd = ByteArray(0xFF) { it.toByte() }
        assertEquals(config(maxCsd), AudioPayload.decodeConfig(AudioPayload.encodeConfig(config(maxCsd))))
    }

    @Test
    fun `config rejects invalid fields on encode`() {
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config().copy(codec = 2.toByte()))
        }
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config().copy(sampleRate = 0))
        }
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config().copy(channelCount = 0))
        }
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config().copy(channelCount = 256))
        }
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config().copy(bitrate = -1))
        }
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeConfig(config(ByteArray(0x100)))
        }
    }

    @Test
    fun `config rejects invalid fields on decode`() {
        val encoded = AudioPayload.encodeConfig(config())
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOf().also { it[0] = 2 })
        }
        // sampleRate 在偏移 1..4
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOf().also { it[4] = 0; it[1] = 0; it[2] = 0; it[3] = 0 })
        }
        // channelCount 在偏移 5
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOf().also { it[5] = 0 })
        }
        // bitrate 在偏移 6..9，置符号位
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOf().also { it[6] = 0x80.toByte() })
        }
    }

    @Test
    fun `config rejects truncated payload and csd length mismatch`() {
        val encoded = AudioPayload.encodeConfig(config())
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOfRange(0, 10))
        }
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOfRange(0, encoded.size - 1))
        }
        // csdLen sits at offset 10; declaring 3 bytes while only 2 follow
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeConfig(encoded.copyOf().also { it[10] = 3 })
        }
    }

    @Test
    fun `frame round trip keeps bare aac bytes`() {
        val aac = ByteArray(341) { (it * 7).toByte() }
        val encoded = AudioPayload.encodeFrame(AudioPayload.Frame(1_234_567L, aac))
        assertEquals(4 + aac.size, encoded.size)
        val decoded = AudioPayload.decodeFrame(encoded)
        assertEquals(1_234_567L, decoded.ptsMs)
        assertArrayEquals(aac, decoded.data)
    }

    @Test
    fun `frame pts is unsigned 32 bit and wraps`() {
        val aac = byteArrayOf(0x21, 0x10)
        assertEquals(0L, AudioPayload.decodeFrame(AudioPayload.encodeFrame(AudioPayload.Frame(0L, aac))).ptsMs)
        assertEquals(
            0xFFFF_FFFFL,
            AudioPayload.decodeFrame(AudioPayload.encodeFrame(AudioPayload.Frame(0xFFFF_FFFFL, aac))).ptsMs
        )
        // 超过 2^32 的值和负数都截断到低 32 位。
        assertEquals(
            5L,
            AudioPayload.decodeFrame(AudioPayload.encodeFrame(AudioPayload.Frame(0x1_0000_0005L, aac))).ptsMs
        )
        assertEquals(
            0xFFFF_FFFFL,
            AudioPayload.decodeFrame(AudioPayload.encodeFrame(AudioPayload.Frame(-1L, aac))).ptsMs
        )
    }

    @Test
    fun `frame rejects empty payload and header-only payload`() {
        val aac = byteArrayOf(0x21, 0x10)
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeFrame(AudioPayload.Frame(0L, ByteArray(0)))
        }
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeFrame(ByteArray(3))
        }
        expectThrows<ProtocolDecodeException> {
            AudioPayload.decodeFrame(ByteArray(4)) // 只有 PTS，没有 AAC 字节
        }
        assertEquals(aac.size, AudioPayload.decodeFrame(AudioPayload.encodeFrame(AudioPayload.Frame(0L, aac))).data.size)
    }

    @Test
    fun `payloads beyond frame codec cap are rejected`() {
        val oversized = ByteArray(FrameCodec.MAX_PAYLOAD_SIZE + 1)
        expectThrows<ProtocolDecodeException> { AudioPayload.decodeFrame(oversized) }
        expectThrows<ProtocolDecodeException> { AudioPayload.decodeConfig(oversized) }
        // 4 字节 PTS + data 后总长比上限多 1 字节。
        expectThrows<IllegalArgumentException> {
            AudioPayload.encodeFrame(AudioPayload.Frame(0L, ByteArray(FrameCodec.MAX_PAYLOAD_SIZE - 3)))
        }
    }

    @Test
    fun `config and frame survive the real 6-byte frame header on the audio channel`() {
        val configPayload = AudioPayload.encodeConfig(config())
        val framePayload = AudioPayload.encodeFrame(AudioPayload.Frame(9_000L, byteArrayOf(0x21, 0x10, 0x05)))

        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, FrameCodec.Frame(Channel.AUDIO, AudioMsg.CONFIG, configPayload))
        FrameCodec.writeFrame(out, FrameCodec.Frame(Channel.AUDIO, AudioMsg.FRAME, framePayload))

        val input = ByteArrayInputStream(out.toByteArray())
        val configFrame = FrameCodec.readFrame(input)!!
        assertEquals(Channel.AUDIO, configFrame.channel)
        assertEquals(AudioMsg.CONFIG, configFrame.messageType)
        assertEquals(config(), AudioPayload.decodeConfig(configFrame.payload))

        val audioFrame = FrameCodec.readFrame(input)!!
        assertEquals(Channel.AUDIO, audioFrame.channel)
        assertEquals(AudioMsg.FRAME, audioFrame.messageType)
        val decoded = AudioPayload.decodeFrame(audioFrame.payload)
        assertEquals(9_000L, decoded.ptsMs)
        assertArrayEquals(byteArrayOf(0x21, 0x10, 0x05), decoded.data)
    }
}
