package com.dilinkauto.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志开关判定（issue #72）。
 *
 * release 下默认关、debug 下默认开；用户显式切换过则一律以用户值为准。
 * 这三条规则同时被设置页初值（[FileLog.isEnabledIn]）与运行时
 * （[FileLog.loadEnabled]）使用，这里把规则本身钉住 —— 之前两者各写一份，
 * release 下开关显示「开」而实际不落盘。
 */
class LogTogglePolicyTest {

    @Test
    fun `release build defaults to off when the user never touched the switch`() {
        // release（debugBuild=false）且用户没设过：历史值是 true 也不能沿用
        assertFalse(resolveLogEnabled(userSet = false, userValue = true, debugBuild = false))
    }

    @Test
    fun `debug build defaults to on when the user never touched the switch`() {
        assertTrue(resolveLogEnabled(userSet = false, userValue = false, debugBuild = true))
    }

    @Test
    fun `explicit user opt-in wins on a release build`() {
        assertTrue(resolveLogEnabled(userSet = true, userValue = true, debugBuild = false))
    }

    @Test
    fun `explicit user opt-out wins on a debug build`() {
        assertFalse(resolveLogEnabled(userSet = true, userValue = false, debugBuild = true))
    }
}
