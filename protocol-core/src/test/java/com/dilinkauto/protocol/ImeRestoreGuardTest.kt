package com.dilinkauto.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeRestore.shouldRestoreIme 单测。
 * （imeRestoreCommands / imeRestoreCommandLine / 非法 ID 拒绝已由 AuditProtocolFixesTest 覆盖。）
 *
 * linkpc 排除是"别把 VD 自己的输入法设回默认、否则会话结束后手机没有可用键盘"的最后防线，
 * 目前零覆盖。
 */
class ImeRestoreGuardTest {

    @Test
    fun `rejects null blank or literal null string`() {
        assertFalse(ImeRestore.shouldRestoreIme(null))
        assertFalse(ImeRestore.shouldRestoreIme(""))
        assertFalse(ImeRestore.shouldRestoreIme("   "))
        assertFalse(ImeRestore.shouldRestoreIme("null"))
    }

    @Test
    fun `rejects linkpc regardless of case`() {
        assertFalse(ImeRestore.shouldRestoreIme("com.android.linkpc/.ImeService"))
        assertFalse(ImeRestore.shouldRestoreIme("LINKPC"))
        assertFalse(ImeRestore.shouldRestoreIme("com.x/LinkPcIme"))
    }

    @Test
    fun `accepts real ime ids`() {
        assertTrue(ImeRestore.shouldRestoreIme("com.baidu.input/.ImeService"))
        assertTrue(ImeRestore.shouldRestoreIme("com.google.android.inputmethod.latin/.LatinIME"))
    }
}
