package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 A：DPI 单一来源解析器的优先级表。
 *
 * 优先级（高 → 低）：用户设置（且不超过本视口安全上限）> 自动标定 > 默认回落。
 * 这里只测纯决策，不碰 Android：现场无法起 VD 的部分（位置/时序）靠 vd-server 的
 * `VdReusePolicy` / `ContentSettleWindow` 单测与实车日志兜底。
 */
class VdDpiResolverTest {

    private val safeMax = 234 // 1926x1054 横屏：1054*80/360

    @Test
    fun `no override resolves to the auto calibrated value`() {
        val r = VdDpiResolver.resolve(requestedOverride = 0, autoDpi = 234, safeMaxDpi = safeMax)
        assertEquals(VdDpiSource.AUTO, r.source)
        assertEquals(234, r.dpi)
        assertEquals("auto", r.token)
        assertNull("合法 Auto 不该打 W", r.warning)
    }

    @Test
    fun `user override inside the viewport safe ceiling wins`() {
        for (dpi in listOf(VdDeployArgs.DPI_OVERRIDE_MIN, 160, 200, safeMax)) {
            val r = VdDpiResolver.resolve(dpi, autoDpi = 234, safeMaxDpi = safeMax)
            assertEquals("override=$dpi 应生效", VdDpiSource.USER_OVERRIDE, r.source)
            assertEquals(dpi, r.dpi)
            assertEquals("user-$dpi", r.token)
            assertNull(r.warning)
        }
    }

    @Test
    fun `override above the viewport safe ceiling falls back with a warning`() {
        // 现场值：override=480，视口安全上限 234 —— 直接用它会把竖屏应用压成细条。
        val r = VdDpiResolver.resolve(requestedOverride = 480, autoDpi = 234, safeMaxDpi = safeMax)
        assertEquals(VdDpiSource.FALLBACK, r.source)
        assertEquals("必须回落到自动标定值而不是夹到 480", 234, r.dpi)
        assertEquals("fallback-480-234", r.token)
        assertNotNull("回落必须带 W 级日志", r.warning)
        assertTrue("W: ${r.warning}", r.warning!!.startsWith("W:") && r.warning!!.contains("234"))
    }

    @Test
    fun `override outside the protocol range falls back instead of clamping to the boundary`() {
        // 999（越界上界）与 119（越界下界）都不许被夹成 480/120 —— 夹边界正是 480 的来源。
        // `<=0` 是"未设置"的历史语义（[VdDeployArgs.coerceDpiOverride] 同口径），不算非法。
        for (raw in listOf(119, 481, 999, 10_000)) {
            val r = VdDpiResolver.resolve(raw, autoDpi = 234, safeMaxDpi = safeMax)
            assertEquals("raw=$raw", VdDpiSource.FALLBACK, r.source)
            assertEquals("raw=$raw 必须回落自动值", 234, r.dpi)
            assertNotNull("raw=$raw 必须打 W", r.warning)
        }
    }

    @Test
    fun `degenerate inputs still produce a usable dpi`() {
        // 视口非法（0/负数）时 autoDpi 已回落到 DEFAULT_FALLBACK_DPI；解析器不得再产出 0。
        val r = VdDpiResolver.resolve(0, autoDpi = 0, safeMaxDpi = 0)
        assertTrue("dpi 必须为正: ${r.dpi}", r.dpi >= 1)
        assertEquals(VdDeployArgs.DPI_OVERRIDE_MIN, r.safeMaxDpi)
    }

    @Test
    fun `source token is shell safe`() {
        // 令牌会原样进设备侧 shell argv，只允许 [A-Za-z0-9._-]。
        for (bad in listOf("a b", "a;reboot", "$(id)", "a|b", "", "-", "x".repeat(64))) {
            assertEquals("bad=$bad", VdDpiResolver.SOURCE_UNKNOWN, VdDpiResolver.sanitizeToken(bad))
        }
        assertEquals("fallback-480-234", VdDpiResolver.sanitizeToken("fallback-480-234"))
        assertEquals("unknown", VdDpiResolver.parseToken(null))
        assertEquals("unknown", VdDpiResolver.parseToken("-"))
        assertEquals("user-240", VdDpiResolver.parseToken("user-240"))
        // 解析器自己产出的令牌必须能过清洗（回归钉子）。
        assertTrue(VdDpiResolver.sanitizeToken("fallback-10000-234") != VdDpiResolver.SOURCE_UNKNOWN)
    }
}
