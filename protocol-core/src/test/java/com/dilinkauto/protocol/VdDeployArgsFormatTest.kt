package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VdDeployArgs.format / coerceDpiOverride 单测。
 * （sanitizeCarHost 与"默认 carHost='-'"已由 AuditProtocolFixesTest 覆盖，这里只补
 * 精确 12 字段布局、等比缩放钳制、bitrate 边界、allowlist 槽位（issue #21）、
 * 会话 token 槽位（issue #180 第三版）与 DPI 来源槽位（issue #202）。
 *
 * format 是 PipelineServer.main() 的 argv 契约，字段顺序/钳制一旦漂移，部署参数就错位。
 */
class VdDeployArgsFormatTest {

    @Test
    fun `format emits exact twelve field layout with empty trailing sentinels`() {
        val out = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            bitrate = 4_000_000, carHost = "10.0.0.7"
        )
        assertEquals("1280 720 160 127.0.0.1 1280 720 24 4000000 10.0.0.7 - - -", out)
        assertEquals(12, out.split(" ").size)
    }

    @Test
    fun `format appends the allowlist as the tenth comma joined field`() {
        val out = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            carHost = "10.0.0.7",
            allowedPackages = listOf("com.autonavi.minimap", "com.baidu.BaiduMap")
        )
        assertEquals(
            "1280 720 160 127.0.0.1 1280 720 24 4000000 10.0.0.7 com.autonavi.minimap,com.baidu.BaiduMap - -",
            out
        )
        assertEquals(12, out.split(" ").size)
    }

    @Test
    fun `format drops allowlist entries that would break the shell line`() {
        // 白名单原样进设备侧 shell 行（app_process argv），非法包名必须丢弃而不是拼接。
        val out = VdDeployArgs.format(
            1280, 720, 160, "127.0.0.1", 1280, 720, 24,
            allowedPackages = listOf("com.autonavi.minimap; rm -rf /", "com.baidu.BaiduMap", " ", "com.baidu.BaiduMap")
        )
        assertEquals("com.baidu.BaiduMap", out.split(" ")[9])
        assertEquals(12, out.split(" ").size)
    }

    @Test
    fun `format appends the dpi source token as the twelfth field`() {
        // issue #202 A：来源令牌只用于引擎启动行取证，非法字符必须退化成未上报哨兵。
        val out = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 234, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            dpiSource = "fallback-480-234"
        )
        assertEquals("fallback-480-234", out.split(" ")[11])
        assertEquals(VdDpiResolver.SOURCE_UNKNOWN, VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 234, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            dpiSource = "a b;reboot"
        ).split(" ")[11])
    }

    @Test
    fun `format appends the session token as the eleventh field`() {
        val out = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            sessionToken = "AbC123xy"
        )
        assertEquals("AbC123xy", out.split(" ")[10])

        // 非法 token（含 shell 元字符 / 空 / "-"）一律退化成未传哨兵：它会进设备侧 shell
        // 行、也会被拼成哨兵文件名。
        for (bad in listOf("a b", "a;reboot", "", "-", "a".repeat(65), "../etc/passwd")) {
            assertEquals(
                "非法 token 必须退化成 ${VdDeployArgs.SESSION_TOKEN_ANY}: $bad",
                VdDeployArgs.SESSION_TOKEN_ANY,
                VdDeployArgs.sanitizeSessionToken(bad)
            )
        }
        assertEquals("AbC123xy", VdDeployArgs.parseSessionToken("AbC123xy"))
        assertEquals("", VdDeployArgs.parseSessionToken("-"))
        assertEquals("", VdDeployArgs.parseSessionToken(null))
    }

    @Test
    fun `parse allowed packages round trips the encoded field`() {
        assertEquals(
            setOf("com.autonavi.minimap", "com.baidu.BaiduMap"),
            VdDeployArgs.parseAllowedPackages("com.autonavi.minimap,com.baidu.BaiduMap")
        )
        // 以下都表示"没下传/不可用" → 不做白名单过滤（保持车机、桌面部署路径行为）。
        assertEquals(emptySet<String>(), VdDeployArgs.parseAllowedPackages("-"))
        assertEquals(emptySet<String>(), VdDeployArgs.parseAllowedPackages(""))
        assertEquals(emptySet<String>(), VdDeployArgs.parseAllowedPackages(null))
        assertEquals(emptySet<String>(), VdDeployArgs.parseAllowedPackages("com.a;reboot"))
    }

    @Test
    fun `format clamps encoder dims preserving aspect ratio not to square`() {
        // 竖屏 1080x2152 必须等比缩到 1080 高度上限，而不是分开 clamp 成方形 1080x1080。
        val out = VdDeployArgs.format(
            vdWidth = 1080, vdHeight = 2152, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1080, encodeHeight = 2152, fps = 24
        )
        val f = out.split(" ")
        val ew = f[4].toInt()
        val eh = f[5].toInt()
        assertFalse("高度应钳到 1080 上限", eh != 1080)
        assertTrue("宽应为偶数", ew % 2 == 0)
        assertTrue("高应为偶数", eh % 2 == 0)
        assertTrue("竖屏宽高比必须保留（不得退化成方形）：ew=$ew eh=$eh", ew < eh)
        assertFalse("不得是方形 1080 1080", out.contains(" 1080 1080 "))
    }

    @Test
    fun `format bitrate outside input range falls back to default`() {
        val base = { br: Int ->
            VdDeployArgs.format(1280, 720, 160, "127.0.0.1", 1280, 720, 24, bitrate = br)
                .split(" ")[7]
        }
        assertEquals("500000", base(500_000))          // INPUT_MIN_BITRATE 边界含内
        assertEquals("20000000", base(20_000_000))     // INPUT_MAX_BITRATE 边界含内
        assertEquals(VideoConfig.DEFAULT_BITRATE.toString(), base(499_999))   // 低于输入下限
        assertEquals(VideoConfig.DEFAULT_BITRATE.toString(), base(20_000_001)) // 高于输入上限
    }

    @Test
    fun `format zero encoder dims yields well formed string`() {
        // encodeWidth/Height=0 → scale 仍为 1（除以 0f 得 Inf，被 minOf 排除），不崩，EW/EH=0
        val out = VdDeployArgs.format(1280, 720, 160, "127.0.0.1", encodeWidth = 0, encodeHeight = 0, fps = 24)
        val f = out.split(" ")
        assertEquals(12, f.size)
        assertEquals("0", f[4])
        assertEquals("0", f[5])
        assertEquals("-", f[9])
        assertEquals("-", f[10])
        assertEquals("-", f[11])
    }

    @Test
    fun `coerce dpi override boundaries`() {
        assertEquals(0, VdDeployArgs.coerceDpiOverride(-1))    // <=0 → 0（Auto）
        assertEquals(0, VdDeployArgs.coerceDpiOverride(0))
        assertEquals(120, VdDeployArgs.coerceDpiOverride(1))   // < MIN → MIN
        assertEquals(120, VdDeployArgs.coerceDpiOverride(119))
        assertEquals(120, VdDeployArgs.coerceDpiOverride(120))
        assertEquals(480, VdDeployArgs.coerceDpiOverride(480))
        assertEquals(480, VdDeployArgs.coerceDpiOverride(481)) // > MAX → MAX
        assertEquals(480, VdDeployArgs.coerceDpiOverride(10_000))
    }
}
