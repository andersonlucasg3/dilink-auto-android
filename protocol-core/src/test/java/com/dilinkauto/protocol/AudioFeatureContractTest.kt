package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #67（S1）契约测试：握手能力位不得凭空声明支持音频。
 *
 * 修复前 `HandshakeRequest.supportedFeatures` 默认值是
 * `FEATURE_VIDEO or FEATURE_AUDIO`，车机与桌面端所有构造点都会「假广告」；
 * 老版本对端据此会被误认为支持 9640 音频通道。
 *
 * 同时钉住双向协商：Request 是「接收端想播」，Response 是「手机能采」，
 * 两者都置 [FEATURE_AUDIO] 才启用音频（Refs #13）。
 */
class AudioFeatureContractTest {

    @Test
    fun `handshake request default must not advertise audio`() {
        val request = HandshakeRequest(
            deviceName = "car",
            screenWidth = 1280,
            screenHeight = 720,
            appVersionCode = 1
        )
        assertEquals(FEATURE_VIDEO, request.supportedFeatures)
    }

    @Test
    fun `builder default must not advertise audio`() {
        val request = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .build()
        assertEquals(FEATURE_VIDEO, request.supportedFeatures)
    }

    @Test
    fun `builder can opt into audio and the bit survives encode decode`() {
        val request = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .supportedFeatures(FEATURE_VIDEO or FEATURE_AUDIO)
            .build()
        assertEquals(FEATURE_VIDEO or FEATURE_AUDIO, request.supportedFeatures)
        assertEquals(request.supportedFeatures, HandshakeRequest.decode(request.encode()).supportedFeatures)
    }

    @Test
    fun `handshake response default must not advertise audio`() {
        val response = HandshakeResponse(
            accepted = true,
            deviceName = "phone",
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertEquals(0, response.supportedFeatures)
    }

    @Test
    fun `handshake response audio bit survives encode decode`() {
        val response = HandshakeResponse(
            accepted = true,
            deviceName = "phone",
            displayWidth = 1080,
            displayHeight = 2400,
            supportedFeatures = FEATURE_VIDEO or FEATURE_AUDIO
        )
        assertEquals(
            FEATURE_VIDEO or FEATURE_AUDIO,
            HandshakeResponse.decode(response.encode()).supportedFeatures
        )
    }

    @Test
    fun `legacy response without trailing features must not enable audio`() {
        val full = HandshakeResponse(
            accepted = true,
            deviceName = "phone",
            displayWidth = 1080,
            displayHeight = 2400,
            supportedFeatures = FEATURE_AUDIO
        ).encode()
        val legacy = HandshakeResponse.decode(full.copyOfRange(0, full.size - 4))
        assertEquals(0, legacy.supportedFeatures)

        val request = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .supportedFeatures(FEATURE_VIDEO or FEATURE_AUDIO)
            .build()
        assertFalse(isAudioNegotiated(request, legacy))
    }

    @Test
    fun `audio requires both peers to advertise it`() {
        val reqWithout = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .build()
        val reqWith = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .supportedFeatures(FEATURE_VIDEO or FEATURE_AUDIO)
            .build()
        val respWithout = HandshakeResponse(
            accepted = true, deviceName = "phone", displayWidth = 1080, displayHeight = 2400
        )
        val respWith = HandshakeResponse(
            accepted = true,
            deviceName = "phone",
            displayWidth = 1080,
            displayHeight = 2400,
            supportedFeatures = FEATURE_AUDIO
        )

        assertFalse(isAudioNegotiated(reqWithout, respWith))
        assertFalse(isAudioNegotiated(reqWith, respWithout))
        assertFalse(isAudioNegotiated(reqWithout, respWithout))
        assertTrue(isAudioNegotiated(reqWith, respWith))
    }

    @Test
    fun `rejected handshake must not enable audio`() {
        val req = HandshakeRequest.builder()
            .deviceName("car")
            .screenSize(1280, 720)
            .appVersionCode(1)
            .supportedFeatures(FEATURE_VIDEO or FEATURE_AUDIO)
            .build()
        val resp = HandshakeResponse(
            accepted = false,
            deviceName = "phone",
            displayWidth = 1080,
            displayHeight = 2400,
            supportedFeatures = FEATURE_AUDIO
        )
        assertFalse(isAudioNegotiated(req, resp))
    }
}
