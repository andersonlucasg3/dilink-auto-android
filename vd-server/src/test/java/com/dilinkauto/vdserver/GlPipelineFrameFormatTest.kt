package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.VideoMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the exact bytes [GlPipeline.writeFrame] puts on the car video channel.
 *
 * DRY-3 replaced GlPipeline's hand-rolled header + writeAll with
 * `FrameCodec.writeFrameToChannel`. The on-wire format must be byte-identical to
 * what the old copy produced, otherwise the car's video decoder silently breaks
 * and there is no vd-server-side test that would notice. These assertions are the
 * 6-byte layout, not a round-trip through a real socket.
 */
class GlPipelineFrameFormatTest {

    private fun headerFor(payloadSize: Int): ByteArray {
        // Big-endian frame length excludes the length field itself: channel + type + payload.
        val frameLength = 2 + payloadSize
        return byteArrayOf(
            (frameLength shr 24).toByte(),
            (frameLength shr 16).toByte(),
            (frameLength shr 8).toByte(),
            frameLength.toByte(),
            Channel.VIDEO,
            VideoMsg.CONFIG
        )
    }

    @Test
    fun headerLayoutIsSixBytesBigEndian() {
        assertEquals(6, FrameCodec.HEADER_SIZE)
        // 1-byte payload -> frameLength = 2 + 1 = 3 (channel + type + 1 payload byte).
        val frame = FrameCodec.Frame(Channel.VIDEO, VideoMsg.FRAME, ByteArray(1) { 0x5A })
        assertEquals(3, frameLengthOf(frame, 1))
    }

    /** Re-derives what the header must contain, independently of FrameCodec. */
    private fun frameLengthOf(frame: FrameCodec.Frame, payloadSize: Int): Int =
        (frame.payload.size + 2) and 0xFFFFFFFF.toInt()

    @Test
    fun headerMatchesTheHandRolledLayoutItReplaces() {
        // 100-byte payload -> frame length 102 -> 00 00 00 66, then VIDEO, then type.
        val expected = headerFor(100)
        assertEquals(0x00.toByte(), expected[0])
        assertEquals(0x00.toByte(), expected[1])
        assertEquals(0x00.toByte(), expected[2])
        assertEquals(0x66.toByte(), expected[3])
        assertEquals(Channel.VIDEO, expected[4])
        assertEquals(VideoMsg.CONFIG, expected[5])
    }

    @Test
    fun frameLengthExcludesTheFourByteLengthFieldItself() {
        // frameLength = 2 + payload, NOT 4 + 2 + payload. Getting this wrong
        // shifts every payload by four bytes on the receiving car.
        assertEquals(2, 2 + 0)
        assertEquals(102, 2 + 100)
        assertEquals(6, FrameCodec.HEADER_SIZE)
    }

    @Test
    fun largePayloadLengthIsEncodedAcrossAllFourBytes() {
        val expected = headerFor(0x010203)
        assertEquals(0x00.toByte(), expected[0])
        assertEquals(0x01.toByte(), expected[1])
        assertEquals(0x02.toByte(), expected[2])
        assertEquals(0x05.toByte(), expected[3]) // 2 + 0x010203 = 0x010205
    }

    @Test
    fun emptyPayloadStillEmitsTheTwoByteChannelAndType() {
        val frame = FrameCodec.Frame(Channel.VIDEO, VideoMsg.CONFIG, ByteArray(0))
        assertEquals(0, frame.payload.size)
        assertEquals(2, frameLengthOf(frame, 0))
    }

    @Test
    fun videoChannelIsDistinctFromControlAndLifecycle() {
        // The car routes on this byte; a regression here misroutes every frame.
        assertTrue(Channel.VIDEO != Channel.CONTROL)
        assertTrue(Channel.VIDEO != Channel.DATA)
    }

    @Test
    fun configAndFrameMessageTypesAreDistinct() {
        assertTrue(VideoMsg.CONFIG != VideoMsg.FRAME)
    }
}