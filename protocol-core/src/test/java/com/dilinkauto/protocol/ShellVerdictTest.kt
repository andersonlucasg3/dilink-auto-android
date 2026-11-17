package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellVerdict] 的判定回归（issue #23）。
 *
 * 现场实测（2026-10-27，MI 9 / Android 15）：
 *   `cmd display power-foo 0` → `Unknown command: power-foo`，`echo $?` = **255**。
 * 因此「未知子命令」必须同时被文本与退出码两条规则拦下；只看输出是否为空会把
 * 失败当成功，只看退出码则在个别返回 0 的 ROM 上漏判。
 */
class ShellVerdictTest {

    @Test
    fun `rc zero is success`() {
        assertTrue(ShellVerdict.succeeded("__DILINK_RC=0\n"))
    }

    @Test
    fun `nonzero rc is failure`() {
        assertFalse(ShellVerdict.succeeded("__DILINK_RC=255\n"))
    }

    @Test
    fun `unknown command fails even with rc zero`() {
        assertFalse(
            ShellVerdict.succeeded("Unknown command: power-reset\n__DILINK_RC=0\n"),
        )
    }

    @Test
    fun `missing rc marker is failure`() {
        assertFalse(ShellVerdict.succeeded("some output without marker"))
        assertFalse(ShellVerdict.succeeded(null))
    }

    @Test
    fun `wrap appends rc marker and merges stderr`() {
        assertEquals(
            "cmd display power-reset 0 2>&1; echo \"__DILINK_RC=$?\"",
            ShellVerdict.wrap("cmd display power-reset 0"),
        )
    }

    @Test
    fun `detail strips the marker and collapses whitespace`() {
        assertEquals(
            "Unknown command: power-reset",
            ShellVerdict.detail("  Unknown command: power-reset  \n__DILINK_RC=255"),
        )
    }

    @Test
    fun `separated stdout and stderr are both considered`() {
        assertTrue(ShellVerdict.succeeded("__DILINK_RC=0", "warning: something"))
        assertFalse(ShellVerdict.succeeded("", "Unknown command: power-reset\n__DILINK_RC=255"))
    }
}
