package com.dilinkauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessagesTest {

    @Test
    fun testHandshakeRequestRoundTrip() {
        val original = HandshakeRequest(
            protocolVersion = PROTOCOL_VERSION,
            deviceName = "TestPhone",
            screenWidth = 1080,
            screenHeight = 2400,
            supportedFeatures = 0x07,
            displayMode = DISPLAY_MODE_VIRTUAL,
            screenDpi = 440,
            appVersionCode = 100,
            targetFps = 60,
            appVersionName = "1.0.0",
            dpiOverride = 213,
            bitrate = 3_000_000,
            vdAreaTier = VdAreaTier.COMPAT_2560
        )
        val encoded = original.encode()
        val decoded = HandshakeRequest.decode(encoded)

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.screenWidth, decoded.screenWidth)
        assertEquals(original.screenHeight, decoded.screenHeight)
        assertEquals(original.supportedFeatures, decoded.supportedFeatures)
        assertEquals(original.displayMode, decoded.displayMode)
        assertEquals(original.screenDpi, decoded.screenDpi)
        assertEquals(original.appVersionCode, decoded.appVersionCode)
        assertEquals(original.targetFps, decoded.targetFps)
        assertEquals(original.appVersionName, decoded.appVersionName)
        assertEquals(original.dpiOverride, decoded.dpiOverride)
        assertEquals(original.bitrate, decoded.bitrate)
        assertEquals(original.vdAreaTier, decoded.vdAreaTier)
    }

    @Test
    fun testHandshakeRequest_AreaTierTailIsBackwardCompatible() {
        // 新 peer 编码带 vdAreaTier 的尾字段；老手机会忽略它不认识的尾部 4B，
        // 等价于把最后 4B 砍掉再按老布局解码 —— 其余字段必须完好。
        val full = HandshakeRequest(
            deviceName = "TestPhone", screenWidth = 1080, screenHeight = 2400,
            screenDpi = 440, appVersionCode = 100, dpiOverride = 213,
            bitrate = 3_000_000, vdAreaTier = VdAreaTier.COMPAT_2560
        ).encode()
        val asSeenByOldPhone = full.copyOfRange(0, full.size - 4)
        val decoded = HandshakeRequest.decode(asSeenByOldPhone)
        assertEquals("TestPhone", decoded.deviceName)
        assertEquals(213, decoded.dpiOverride)
        assertEquals(3_000_000, decoded.bitrate)
        assertEquals(VdAreaTier.AUTO, decoded.vdAreaTier)

        // 新 peer 完整解码拿到档位；老车机不带该字段时同样解出 AUTO。
        assertEquals(VdAreaTier.COMPAT_2560, HandshakeRequest.decode(full).vdAreaTier)
        val newPeer = HandshakeRequest(
            deviceName = "old-car", screenWidth = 1080, screenHeight = 1920,
            appVersionCode = 1
        ).encode()
        // 砍掉尾字段 = 老车机从未下发过该字段 → 手机必须回落默认档。
        assertEquals(
            VdAreaTier.AUTO,
            HandshakeRequest.decode(newPeer.copyOfRange(0, newPeer.size - 4)).vdAreaTier
        )
    }

    @Test
    fun testHandshakeRequest_DpiOverrideOmittedByOldPeer_DecodesAsZero() {
        // An older car that doesn't send the trailing dpiOverride field must
        // decode as 0 (auto), keeping the wire format backward-compatible.
        // Layout: ver(4) nameLen(2) name(4) sw(4) sh(4) feat(4) mode(1) dpi(4) verCode(4) fps(4) verNameLen(2)
        val buf = ByteBuffer.allocate(4 + 2 + 4 + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(4) // nameLen
        buf.put("Test".toByteArray(Charsets.UTF_8))
        buf.putInt(1080) // screenWidth
        buf.putInt(1920) // screenHeight
        buf.putInt(7)    // supportedFeatures
        buf.put(1.toByte()) // displayMode
        buf.putInt(160)  // screenDpi
        buf.putInt(100)  // appVersionCode
        buf.putInt(60)   // targetFps
        buf.putShort(0)  // verNameLen
        // No trailing dpiOverride — simulates an old peer.

        val decoded = HandshakeRequest.decode(buf.array())
        assertEquals(0, decoded.dpiOverride)
        assertEquals(0, decoded.bitrate)
        // 缺 vdAreaTier 尾字段也必须回落默认档，而不是误用兼容档把面积放回去。
        assertEquals(VdAreaTier.AUTO, decoded.vdAreaTier)
    }

    @Test
    fun testHandshakeRequestUnsignedLengthPreventsNegativeArraySize() {
        // Construct a buffer where nameLen has the sign bit set (e.g. 0x8004.toShort())
        // Without `and 0xFFFF`, this would be -32764 and throw NegativeArraySizeException
        val len = 0x8004.toShort()
        val buf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(len)
        val data = buf.array()

        try {
            HandshakeRequest.decode(data)
            fail("Expected ProtocolDecodeException due to truncated buffer")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: signed short was not masked to unsigned!")
        } catch (e: ProtocolDecodeException) {
            // Expected: parsed len as positive 32772, declared length exceeds remaining buffer
        }
    }

    @Test
    fun testHandshakeResponseRoundTrip() {
        val original = HandshakeResponse(
            protocolVersion = PROTOCOL_VERSION,
            accepted = true,
            deviceName = "CarUnit",
            displayWidth = 1920,
            displayHeight = 1080,
            virtualDisplayId = 42,
            adbPort = 5555,
            vdServerJarPath = "/data/local/tmp/vd-server.jar",
            connectionMethod = CONNECTION_METHOD_USB_ADB,
            vdDpi = 160,
            vdWidth = 3192,
            vdHeight = 1794,
            supportedFeatures = FEATURE_VIDEO or FEATURE_AUDIO
        )
        val encoded = original.encode()
        val decoded = HandshakeResponse.decode(encoded)

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.accepted, decoded.accepted)
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.displayWidth, decoded.displayWidth)
        assertEquals(original.displayHeight, decoded.displayHeight)
        assertEquals(original.virtualDisplayId, decoded.virtualDisplayId)
        assertEquals(original.adbPort, decoded.adbPort)
        assertEquals(original.vdServerJarPath, decoded.vdServerJarPath)
        assertEquals(original.connectionMethod, decoded.connectionMethod)
        assertEquals(original.vdDpi, decoded.vdDpi)
        assertEquals(original.vdWidth, decoded.vdWidth)
        assertEquals(original.vdHeight, decoded.vdHeight)
        assertEquals(original.supportedFeatures, decoded.supportedFeatures)
    }

    /**
     * 旧对端兼容：payload 在 vdDpi 之后截断（老手机没编 vdWidth/vdHeight，
     * 也没有 S1 新增的 supportedFeatures）时必须全部解出 0，而不是抛
     * BufferUnderflow。0 = 不支持音频，是安全的回退方向。
     */
    @Test
    fun testHandshakeResponseTrailingFieldsDefaultToZeroOnLegacyPayload() {
        val full = HandshakeResponse(
            accepted = true,
            deviceName = "OldPhone",
            displayWidth = 1280,
            displayHeight = 720,
            vdDpi = 200,
            vdWidth = 3192,
            vdHeight = 1794,
            supportedFeatures = FEATURE_VIDEO or FEATURE_AUDIO
        ).encode()
        // 截到 vdDpi 字段末尾（丢弃 vdWidth/vdHeight 两个 4B + supportedFeatures 4B）
        val legacy = full.copyOfRange(0, full.size - 12)
        val decoded = HandshakeResponse.decode(legacy)

        assertEquals(200, decoded.vdDpi)
        assertEquals(0, decoded.vdWidth)
        assertEquals(0, decoded.vdHeight)
        assertEquals(0, decoded.supportedFeatures)
    }

    /**
     * 中间版本兼容：S1 之前 main 上的手机已经会编 vdWidth/vdHeight，但尚无
     * trailing supportedFeatures 这 4 字节。新解码器读不到它必须回退 0，
     * 不能把前面的 vdHeight 当能力位，也不能抛异常。
     */
    @Test
    fun testHandshakeResponseMissingTrailingAudioFeatureDecodesAsZero() {
        val full = HandshakeResponse(
            accepted = true,
            deviceName = "PreS1Phone",
            displayWidth = 1280,
            displayHeight = 720,
            vdWidth = 3192,
            vdHeight = 1794,
            supportedFeatures = FEATURE_AUDIO
        ).encode()
        val preS1 = full.copyOfRange(0, full.size - 4)
        val decoded = HandshakeResponse.decode(preS1)

        assertEquals(3192, decoded.vdWidth)
        assertEquals(1794, decoded.vdHeight)
        assertEquals(0, decoded.supportedFeatures)
    }

    @Test
    fun testHandshakeResponseUnsignedLength() {
        val buf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(1.toByte()) // accepted
        buf.putShort(0x8002.toShort()) // nameLen with sign bit
        val data = buf.array()

        try {
            HandshakeResponse.decode(data)
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: signed short was not masked to unsigned!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testAppListMessageRoundTrip() {
        val apps = listOf(
            AppInfo("com.example.app1", "App One", AppCategory.MUSIC, iconPng = byteArrayOf(10, 20), iconHash = "hash1"),
            AppInfo("com.example.app2", "App Two", AppCategory.NAVIGATION, iconPng = byteArrayOf(), iconHash = "")
        )
        val original = AppListMessage(apps)
        val encoded = original.encode()
        val decoded = AppListMessage.decode(encoded)

        assertEquals(2, decoded.apps.size)
        assertEquals("com.example.app1", decoded.apps[0].packageName)
        assertEquals("App One", decoded.apps[0].appName)
        assertEquals(AppCategory.MUSIC, decoded.apps[0].category)
        assertArrayEquals(byteArrayOf(10, 20), decoded.apps[0].iconPng)
        assertEquals("hash1", decoded.apps[0].iconHash)

        assertEquals("com.example.app2", decoded.apps[1].packageName)
        assertEquals("App Two", decoded.apps[1].appName)
        assertEquals(AppCategory.NAVIGATION, decoded.apps[1].category)
    }

    @Test
    fun testMediaMetadataRoundTrip() {
        val original = MediaMetadata(
            title = "Song Title",
            artist = "Artist Name",
            album = "Album Name",
            durationMs = 210000L
        )
        val encoded = original.encode()
        val decoded = MediaMetadata.decode(encoded)

        assertEquals(original.title, decoded.title)
        assertEquals(original.artist, decoded.artist)
        assertEquals(original.album, decoded.album)
        assertEquals(original.durationMs, decoded.durationMs)
    }

    @Test
    fun testAppInfoDataMessageRoundTrip() {
        val original = AppInfoDataMessage(
            packageName = "com.test.app",
            appName = "Test App",
            versionName = "1.0",
            versionCode = 100L,
            installTime = 1234567890L,
            targetSdk = 33
        )
        val encoded = original.encode()
        val decoded = AppInfoDataMessage.decode(encoded)
        assertEquals(original.packageName, decoded.packageName)
        assertEquals(original.appName, decoded.appName)
        assertEquals(original.versionName, decoded.versionName)
        assertEquals(original.versionCode, decoded.versionCode)
        assertEquals(original.installTime, decoded.installTime)
        assertEquals(original.targetSdk, decoded.targetSdk)
    }

    @Test
    fun testHandshakeRequestTruncatedAfterDeviceNameThrowsProtocolDecodeException() {
        val buf = ByteBuffer.allocate(4 + 2 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(4)
        buf.put("Test".toByteArray(Charsets.UTF_8))
        buf.putInt(1080)
        val data = buf.array()

        try {
            HandshakeRequest.decode(data)
            fail("Expected ProtocolDecodeException")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testHandshakeResponseTruncatedAfterDeviceNameThrowsProtocolDecodeException() {
        val buf = ByteBuffer.allocate(4 + 1 + 2 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(1.toByte())
        buf.putShort(4)
        buf.put("Test".toByteArray(Charsets.UTF_8))
        buf.putInt(1920)
        val data = buf.array()

        try {
            HandshakeResponse.decode(data)
            fail("Expected ProtocolDecodeException")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testHandshakeResponseTruncatedOptionalIntsHandledGracefully() {
        val buf = ByteBuffer.allocate(4 + 1 + 2 + 4 + 4 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(1.toByte())
        buf.putShort(4)
        buf.put("Test".toByteArray(Charsets.UTF_8))
        buf.putInt(1920)
        buf.putInt(1080)
        buf.putShort(0x1234.toShort())
        val data = buf.array()

        val decoded = HandshakeResponse.decode(data)
        assertEquals(-1, decoded.virtualDisplayId)
        assertEquals(Ports.ADB_PORT, decoded.adbPort)
    }
}
