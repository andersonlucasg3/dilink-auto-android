package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the 2026-10-09 full-project audit fixes in
 * protocol-core (see docs/audit-project-2026-10-09.md):
 *
 *  - P-01: every fixed-size `ByteBuffer.getX()` in the decoders is now preceded
 *    by a `require()` and raises ProtocolDecodeException — never the unchecked
 *    BufferUnderflowException that used to escape the reader coroutine.
 *  - P-L4: a truncated icon/size fails the decode instead of silently
 *    desynchronising the iconHash read.
 *  - P-L5: unknown enum ids are corruption, not a live default.
 *  - S-02: peer-supplied package/component names are shape-validated at the
 *    decoder boundary (they are interpolated into shell UID `am start` /
 *    `pm uninstall` lines in vd-server).
 *  - S-01: the deploy argv carries a sanitised CAR_HOST slot.
 *  - P-M3: protocol version is validated.
 *  - P-L7: negative bitrates render sanely.
 *  - P-L9: IME restore commands are quoted and the id shape-validated.
 */
class AuditProtocolFixesTest {

    // ─── P-01: truncated fixed-size fields ───

    @Test
    fun handshakeRequest_decode_rejectsShortPayloads() {
        for (len in 0..3) {
            try {
                HandshakeRequest.decode(ByteArray(len))
                fail("HandshakeRequest.decode must reject a $len-byte payload")
            } catch (e: ProtocolDecodeException) {
                assertTrue(e.message!!.contains("Need"))
            }
        }
    }

    @Test
    fun handshakeResponse_decode_rejectsShortPayloads() {
        // version(4) + accepted(1) are the mandatory fixed part.
        for (len in 0..4) {
            try {
                HandshakeResponse.decode(ByteArray(len))
                fail("HandshakeResponse.decode must reject a $len-byte payload")
            } catch (e: ProtocolDecodeException) { /* expected */ }
        }
        // 5 bytes fixed part present, but deviceName length prefix missing.
        try {
            HandshakeResponse.decode(ByteArray(5))
            fail("HandshakeResponse.decode must reject a missing deviceName prefix")
        } catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun appListMessage_decode_rejectsShortPayloadsAndTruncatedEntries() {
        try { AppListMessage.decode(ByteArray(0)); fail("empty APP_LIST must throw") }
        catch (e: ProtocolDecodeException) { /* expected */ }
        try { AppListMessage.decode(ByteArray(1)); fail("1-byte APP_LIST must throw") }
        catch (e: ProtocolDecodeException) { /* expected */ }

        // count=1, then a complete pkg+name, then nothing: category byte missing.
        val truncatedEntry = ByteBufferBuilder().putShort(1).putShortPrefixed("com.a").putShortPrefixed("A").build()
        try {
            AppListMessage.decode(truncatedEntry)
            fail("truncated app entry must throw")
        } catch (e: ProtocolDecodeException) { /* expected */ }

        // count=1, pkg+name+category present, then a negative icon size.
        val negativeIcon = ByteBufferBuilder()
            .putShort(1).putShortPrefixed("com.a").putShortPrefixed("A").put(3).putInt(-1).build()
        try {
            AppListMessage.decode(negativeIcon)
            fail("negative icon size must throw")
        } catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun appListMessage_decode_truncatedIcon_isADecodeFailure_notASilentDrop() {
        // P-L4: iconSize declares 100 bytes but only 4 are present. The old
        // lenient path dropped the icon AND consumed the icon bytes as the
        // iconHash length prefix, caching a garbage hash under a real key.
        val payload = ByteBufferBuilder()
            .putShort(1)
            .putShortPrefixed("com.a")
            .putShortPrefixed("A")
            .put(0)
            .putInt(100)
            .put(byteArrayOf(1, 2, 3, 4))
            .build()
        try {
            AppListMessage.decode(payload)
            fail("truncated icon must throw, not silently produce a garbage hash")
        } catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun mediaMetadata_decode_rejectsMissingDuration() {
        val noTail = ByteBufferBuilder().putShortPrefixed("t").putShortPrefixed("a").putShortPrefixed("b").build()
        try { MediaMetadata.decode(noTail); fail("missing durationMs must throw") }
        catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun appInfoDataMessage_decode_rejectsMissingTail() {
        val payload = ByteBufferBuilder()
            .putShortPrefixed("com.a").putShortPrefixed("A").putShortPrefixed("1.0").build()
        try { AppInfoDataMessage.decode(payload); fail("missing version/install/sdk must throw") }
        catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun validMessages_stillRoundTrip() {
        val req = HandshakeRequest(deviceName = "car", screenWidth = 1280, screenHeight = 720, appVersionCode = 7)
        assertEquals(req, HandshakeRequest.decode(req.encode()))

        val resp = HandshakeResponse(
            accepted = true, deviceName = "phone",
            displayWidth = 1280, displayHeight = 720,
            adbPort = 5555, vdServerJarPath = VdDeploy.JAR_PATH,
            vdWidth = 1408, vdHeight = 792
        )
        assertEquals(resp, HandshakeResponse.decode(resp.encode()))

        val apps = AppListMessage(listOf(
            AppInfo("com.a", "A", AppCategory.NAVIGATION, byteArrayOf(1, 2, 3), "hash1"),
            AppInfo("com.b", "B", AppCategory.OTHER, ByteArray(0), "")
        ))
        val decoded = AppListMessage.decode(apps.encode())
        assertEquals(2, decoded.apps.size)
        assertEquals("com.a", decoded.apps[0].packageName)
        assertTrue(decoded.apps[0].iconPng.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("hash1", decoded.apps[0].iconHash)
        assertTrue(decoded.apps[1].iconPng.isEmpty())
    }

    // ─── P-L5: unknown enum ids are corruption ───

    @Test
    fun unknownEnumIds_areRejected_notCoerced() {
        try { AppCategory.fromId(99.toByte()); fail("unknown AppCategory must throw") }
        catch (e: ProtocolDecodeException) { /* expected */ }
        try { MediaAction.fromId(99.toByte()); fail("unknown MediaAction must throw (was silently PLAY)") }
        catch (e: ProtocolDecodeException) { /* expected */ }
        assertEquals(AppCategory.NAVIGATION, AppCategory.fromId(0))
        assertEquals(MediaAction.PAUSE, MediaAction.fromId(1))
    }

    // ─── S-02: package/component validation at the decoder boundary ───

    @Test
    fun launchAppMessage_rejectsShellMetacharacters() {
        val attacks = listOf(
            "x; id",
            "x$(id)",
            "x`id`",
            "x y",
            "x'||id||'",
            "x|id",
            "x>y",
            "",
            "com.evil;pkg"
        )
        for (attack in attacks) {
            try {
                LaunchAppMessage.decode(attack.toByteArray(Charsets.UTF_8))
                fail("LaunchAppMessage must reject payload '$attack'")
            } catch (e: ProtocolDecodeException) { /* expected */ }
        }
        assertEquals("com.autonavi.minimap", LaunchAppMessage.decode("com.autonavi.minimap".toByteArray()).packageName)
    }

    @Test
    fun appUninstalledMessage_rejectsShellMetacharacters() {
        try {
            AppUninstalledMessage.decode("x; pm install /sdcard/evil.apk".toByteArray())
            fail("AppUninstalledMessage must reject injection payloads")
        } catch (e: ProtocolDecodeException) { /* expected */ }
    }

    @Test
    fun componentName_requiresPackageSlashClass_shape() {
        assertTrue(COMPONENT_REGEX.matches("com.a/.MainActivity"))
        assertTrue(COMPONENT_REGEX.matches("com.a/com.a.Inner\$Activity"))
        assertFalse(COMPONENT_REGEX.matches("com.a"))
        assertFalse(COMPONENT_REGEX.matches("com.a/ev il"))
        assertFalse(COMPONENT_REGEX.matches("com.a/x;id"))
    }

    // ─── S-01: CAR_HOST argv slot ───

    @Test
    fun deployArgs_carHostSlot_isSanitised() {
        val base = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24
        )
        // issue #21 之后 argv 末位多了一个 allowlist 槽位（也是 `-` 哨兵），
        // 所以 CAR_HOST 的判定改成按下标取字段，而不是 endsWith。
        assertEquals("default must be the ANY sentinel",
            VdDeployArgs.CAR_HOST_ANY, base.split(" ")[8])

        assertEquals("192.168.1.5", VdDeployArgs.sanitizeCarHost("192.168.1.5"))
        assertEquals("car.local", VdDeployArgs.sanitizeCarHost("car.local"))
        assertEquals(VdDeployArgs.CAR_HOST_ANY, VdDeployArgs.sanitizeCarHost(""))
        assertEquals(VdDeployArgs.CAR_HOST_ANY, VdDeployArgs.sanitizeCarHost(null))
        assertEquals(VdDeployArgs.CAR_HOST_ANY, VdDeployArgs.sanitizeCarHost("1.2.3.4; id"))
        assertEquals(VdDeployArgs.CAR_HOST_ANY, VdDeployArgs.sanitizeCarHost("$(id)"))
        assertEquals(VdDeployArgs.CAR_HOST_ANY, VdDeployArgs.sanitizeCarHost("a".repeat(254)))

        val pinned = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            carHost = "192.168.1.5"
        )
        assertEquals("192.168.1.5", pinned.split(" ")[8])
        assertEquals("allowlist 槽位保持未下传哨兵", VdDeployArgs.ALLOWED_ANY, pinned.split(" ")[9])

        val plan = VdDeploy.buildDeployPlan(
            jarPath = VdDeploy.JAR_PATH, logPath = VdDeploy.LOG_PATH,
            vdWidth = 1280, vdHeight = 720, dpi = 160,
            encodeWidth = 1280, encodeHeight = 720,
            phoneHost = "127.0.0.1", fps = 24, background = true,
            carHost = "10.0.0.7"
        )
        assertTrue(plan.launchCommand.contains(" 10.0.0.7 "))
    }

    // ─── P-M3: protocol version ───

    @Test
    fun protocolVersion_isValidated() {
        assertEquals(1, requireSupportedProtocolVersion(PROTOCOL_VERSION))
        try { requireSupportedProtocolVersion(999); fail("version 999 must be rejected") }
        catch (e: ProtocolDecodeException) { /* expected */ }
        try { requireSupportedProtocolVersion(0); fail("version 0 must be rejected") }
        catch (e: ProtocolDecodeException) { /* expected */ }
    }

    // ─── P-L7: negative bitrate label ───

    @Test
    fun formatBitrateMbps_clampsNegatives() {
        assertEquals("0M", SettingsFormat.formatBitrateMbps(0))
        assertEquals("0M", SettingsFormat.formatBitrateMbps(-500_000))
        assertEquals("0M", SettingsFormat.formatBitrateMbps(-4_000_000))
        assertEquals("4M", SettingsFormat.formatBitrateMbps(4_000_000))
        assertEquals("2.5M", SettingsFormat.formatBitrateMbps(2_500_000))
    }

    // ─── P-L9: IME restore quoting ───

    @Test
    fun imeRestoreCommands_areQuotedAndValidated() {
        val cmds = ImeRestore.imeRestoreCommands("com.a/.ImeService")
        assertEquals(3, cmds.size)
        for (cmd in cmds) {
            assertTrue("every value must be single-quoted: $cmd", cmd.contains("'com.a/.ImeService'"))
        }
        assertTrue(ImeRestore.imeRestoreCommandLine("com.a/.ImeService").startsWith("ime enable 'com.a/.ImeService'"))

        try {
            ImeRestore.imeRestoreCommands("com.a; rm -rf /")
            fail("unsafe IME id must be rejected")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ─── P-L6: encode-side 16-bit prefix guard ───

    @Test
    fun encode_rejectsPayloadsThatOverflowTheLengthPrefix() {
        val huge = "x".repeat(70_000) // > 0xFFFF UTF-8 bytes
        try { HandshakeRequest(deviceName = huge, screenWidth = 1, screenHeight = 1, appVersionCode = 1).encode(); fail() }
        catch (e: IllegalArgumentException) { /* expected */ }
        try { MediaMetadata(huge, "a", "b", 1L).encode(); fail() }
        catch (e: IllegalArgumentException) { /* expected */ }
        try { AppListMessage(listOf(AppInfo("com.a", huge, AppCategory.OTHER))).encode(); fail() }
        catch (e: IllegalArgumentException) { /* expected */ }
    }

    /** Minimal big-endian builder mirroring the wire helpers used by the decoders. */
    private class ByteBufferBuilder {
        private val out = java.io.ByteArrayOutputStream()
        fun putShort(value: Int) = apply { out.write((value shr 8) and 0xFF); out.write(value and 0xFF) }
        fun putShortPrefixed(value: String) = apply {
            val b = value.toByteArray(Charsets.UTF_8)
            putShort(b.size); out.write(b)
        }
        fun put(value: Int) = apply { out.write(value) }
        fun put(value: ByteArray) = apply { out.write(value) }
        fun putInt(value: Int) = apply {
            out.write((value shr 24) and 0xFF); out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF); out.write(value and 0xFF)
        }
        fun build() = out.toByteArray()
    }
}
