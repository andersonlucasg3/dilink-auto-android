package com.dilinkauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdversarialDecodingTest {

    // ─── 1. HandshakeRequest Boundaries ───

    @Test
    fun testHandshakeRequest_MSBLength_ThrowsUnderflowNotNegativeArray() {
        val lengths = listOf(0x8000.toShort(), 0x8001.toShort(), 0xFFFF.toShort())
        for (l in lengths) {
            val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(PROTOCOL_VERSION)
            buf.putShort(l)
            buf.put("ShortBuf".toByteArray(Charsets.UTF_8))
            val data = buf.array()

            try {
                HandshakeRequest.decode(data)
                fail("Expected ProtocolDecodeException for length $l")
            } catch (e: NegativeArraySizeException) {
                fail("NegativeArraySizeException thrown for length $l: MSB not masked!")
            } catch (e: ProtocolDecodeException) {
                // Pass: length correctly treated as unsigned > 32767 and bounds-checked
            }
        }
    }

    @Test
    fun testHandshakeRequest_EmptyStringsAndPayloads() {
        val req = HandshakeRequest(
            protocolVersion = PROTOCOL_VERSION,
            deviceName = "",
            screenWidth = 0,
            screenHeight = 0,
            supportedFeatures = 0,
            displayMode = 0,
            screenDpi = 0,
            appVersionCode = 0,
            targetFps = 0,
            appVersionName = ""
        )
        val encoded = req.encode()
        val decoded = HandshakeRequest.decode(encoded)
        assertEquals("", decoded.deviceName)
        assertEquals("", decoded.appVersionName)
    }

    @Test
    fun testHandshakeRequest_MaxUnsignedLengthWithActualPayload() {
        val targetLen = 40000 // exceeds signed short range 32767
        val bigPayload = ByteArray(targetLen) { (it % 26 + 65).toByte() }
        val buf = ByteBuffer.allocate(4 + 2 + targetLen + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 2 + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(targetLen.toShort())
        buf.put(bigPayload)
        buf.putInt(1080)
        buf.putInt(1920)
        buf.putInt(7)
        buf.put(1.toByte())
        buf.putInt(160)
        buf.putInt(1)
        buf.putInt(60)
        buf.putShort(0.toShort())
        buf.putInt(0) // dpiOverride

        val decoded = HandshakeRequest.decode(buf.array())
        assertEquals(targetLen, decoded.deviceName.length)
    }

    // ─── 2. HandshakeResponse Boundaries ───

    @Test
    fun testHandshakeResponse_MSBLengths() {
        val buf = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(1.toByte())
        buf.putShort(0xFFFF.toShort()) // nameLen = 65535
        try {
            HandshakeResponse.decode(buf.array())
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: MSB not masked!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testHandshakeResponse_EmptyStrings() {
        val resp = HandshakeResponse(
            protocolVersion = PROTOCOL_VERSION,
            accepted = false,
            deviceName = "",
            displayWidth = 0,
            displayHeight = 0,
            virtualDisplayId = -1,
            adbPort = 0,
            vdServerJarPath = "",
            connectionMethod = 0,
            vdDpi = 0
        )
        val decoded = HandshakeResponse.decode(resp.encode())
        assertEquals("", decoded.deviceName)
        assertEquals("", decoded.vdServerJarPath)
    }

    // ─── 3. AppListMessage Boundaries ───

    @Test
    fun testAppListMessage_MSBCount() {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x8001.toShort()) // count = 32769
        try {
            AppListMessage.decode(buf.array())
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: count MSB not masked!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testAppListMessage_EmptyList() {
        val msg = AppListMessage(emptyList())
        val decoded = AppListMessage.decode(msg.encode())
        assertTrue(decoded.apps.isEmpty())
    }

    // ─── 5. MediaMetadata Boundaries ───

    @Test
    fun testMediaMetadata_MSBLengths() {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x8005.toShort())
        try {
            MediaMetadata.decode(buf.array())
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    // ─── 6. AppInfoData Boundaries ───

    @Test
    fun testAppInfoDataMessage_MSBLengths() {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x8000.toShort()) // pkgLen
        try {
            AppInfoDataMessage.decode(buf.array())
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    // ─── 7. FrameCodec Payload Limits ───

    @Test
    fun testFrameCodec_EmptyPayload() {
        val frame = FrameCodec.Frame(Channel.CONTROL, 0x01.toByte(), ByteArray(0))
        val pipeIn = java.io.PipedInputStream()
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        FrameCodec.writeFrame(pipeOut, frame)
        val decoded = FrameCodec.readFrame(pipeIn)
        assertNotNull(decoded)
        assertEquals(frame.channel, decoded!!.channel)
        assertEquals(frame.messageType, decoded.messageType)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun testFrameCodec_OversizedPayloadRejection() {
        val oversized = ByteArray(FrameCodec.MAX_PAYLOAD_SIZE + 1)
        val frame = FrameCodec.Frame(Channel.VIDEO, 0x01.toByte(), oversized)
        val out = java.io.ByteArrayOutputStream()
        try {
            FrameCodec.writeFrame(out, frame)
            fail("Expected IllegalArgumentException for payload > MAX_PAYLOAD_SIZE")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
