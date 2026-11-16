package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 评审第 2 条 / 终审第 2 条：设置页 DPI 提示的钉子（状态 + 参数，断言文案）。
 *
 * 评审原话："越界回落对用户不可见 —— 设置页仍显示用户填的 480、实际生效 234，
 * 用户会以为没生效。" 终审要求文案走 `stringResource`（英文环境不能掉出中文），
 * 所以这里只钉**状态枚举与参数**，文案本身在 `values` / `values-zh` / `values-zh-rCN`
 * 的 `strings.xml` 里。
 */
class VdDpiHintTextTest {

    @Test
    fun `before any handshake the hint is the static state`() {
        // 拿不到视口/生效值 → 静态提示（不留空，也不编造数字）。
        assertEquals(DpiHintState.NO_HANDSHAKE, DpiHint.of(480, 0, 0).state)
        assertEquals(DpiHintState.NO_HANDSHAKE, DpiHint.of(0, 234, 0).state)
    }

    @Test
    fun `an over-cap setting carries the effective value and the cap`() {
        // 现场：设置里是 480，视口上限 234，实际按自动标定值生效。
        val hint = DpiHint.of(startupDpi = 480, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals(DpiHintState.OVER_CAP, hint.state)
        assertEquals(234, hint.effectiveDpi)
        assertEquals(234, hint.safeMaxDpi)
        assertEquals(480, hint.startupDpi)
    }

    @Test
    fun `an in-range setting is reported as used as-is`() {
        val hint = DpiHint.of(startupDpi = 200, effectiveDpi = 200, safeMaxDpi = 234)
        assertEquals(DpiHintState.IN_RANGE, hint.state)
        assertEquals(200, hint.startupDpi)
    }

    @Test
    fun `an out-of-range stored value is invalid (not merely over the cap)`() {
        // 手改 prefs / 老版本残留：低于协议下界走 INVALID 分支（高于上界会先命中 OVER_CAP）。
        val hint = DpiHint.of(startupDpi = 60, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals(DpiHintState.INVALID, hint.state)
        assertEquals(60, hint.startupDpi)
        assertEquals(DpiHintState.OVER_CAP, DpiHint.of(999, 234, 234).state)
    }

    @Test
    fun `auto mode only reports the facts`() {
        val hint = DpiHint.of(startupDpi = 0, effectiveDpi = 234, safeMaxDpi = 234)
        assertEquals(DpiHintState.AUTO, hint.state)
        assertEquals(234, hint.effectiveDpi)
        assertEquals(234, hint.safeMaxDpi)
    }

    @Test
    fun `the hint source contains no hardcoded Chinese literals`() {
        // 源码钉子：中文只能出现在注释/KDoc 里，字符串字面量必须走 stringResource。
        val source = listOf(
            java.io.File("src/main/java/com/dilinkauto/server/ui/screen/VdDpiHintText.kt"),
            java.io.File("app-server/src/main/java/com/dilinkauto/server/ui/screen/VdDpiHintText.kt")
        ).firstOrNull { it.exists() }
        assertTrue("找不到 VdDpiHintText.kt 源文件", source != null)
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val offenders = source!!.readLines().filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.filter { line ->
            val quoted = line.split('"').filterIndexed { i, _ -> i % 2 == 1 }
            quoted.any { cjk.containsMatchIn(it) }
        }
        assertTrue("字符串字面量里仍有中文（应改用 R.string.*）: $offenders", offenders.isEmpty())
    }
}
