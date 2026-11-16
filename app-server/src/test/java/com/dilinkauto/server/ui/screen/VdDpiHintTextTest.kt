package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 评审第 2 条：设置页 DPI 提示的文案钉子。
 *
 * 评审原话："越界回落对用户不可见 —— 设置页仍显示用户填的 480、实际生效 234，
 * 用户会以为没生效。" 这里把四种态（未握手/未设置/越界/合法）的文案固定下来，
 * 数据来自现有握手响应（`effectiveVdDpi` / `effectiveVdSafeMaxDpi`），不新增协议。
 */
class VdDpiHintTextTest {

    @Test
    fun `before any handshake the static hint is shown`() {
        // 拿不到视口/生效值 → 至少要有静态提示，不能空白。
        assertEquals(VdDpiHintText.STATIC, VdDpiHintText.text(startupDpi = 480, effectiveDpi = 0, safeMaxDpi = 0))
        assertEquals(VdDpiHintText.STATIC, VdDpiHintText.text(startupDpi = 0, effectiveDpi = 234, safeMaxDpi = 0))
        assertTrue(VdDpiHintText.STATIC.contains("120..480"))
    }

    @Test
    fun `an over-cap setting states both the effective value and the cap`() {
        // 现场：设置里是 480，视口上限 234，实际按自动标定值生效。
        val text = VdDpiHintText.text(startupDpi = 480, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals(
            "上次实际生效 234dpi；当前视口上限 234dpi，设置 480dpi 超出，将按自动标定值使用",
            text
        )
        // 必须同时出现"实际生效"与"上限"，否则用户仍看不出差别。
        assertTrue(text.contains("实际生效 234"))
        assertTrue(text.contains("上限 234"))
        assertTrue(text.contains("480"))
    }

    @Test
    fun `an in-range setting says it will be used as-is`() {
        assertEquals(
            "上次实际生效 200dpi；当前视口上限 234dpi，设置 200dpi 会原样生效",
            VdDpiHintText.text(startupDpi = 200, effectiveDpi = 200, safeMaxDpi = 234)
        )
    }

    @Test
    fun `an invalid stored value is called out`() {
        // 手改 prefs / 老版本残留：480 之外的值（如 999）同样要提示会被自动标定取代。
        val text = VdDpiHintText.text(startupDpi = 999, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals(
            "上次实际生效 234dpi；当前视口上限 234dpi，设置 999dpi 超出，将按自动标定值使用",
            text
        )
    }

    @Test
    fun `auto mode only reports the facts`() {
        val text = VdDpiHintText.text(startupDpi = 0, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals("上次实际生效 234dpi；当前视口上限 234dpi", text)
    }
}
