package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

class ExceptionUtilTest {

    @Test
    fun describeReturnsMessageWhenPresent() {
        val ex = RuntimeException("something broke")
        assertEquals("something broke", ex.describe())
    }

    @Test
    fun describeTrimsWhitespace() {
        val ex = RuntimeException("  padded message  ")
        assertEquals("padded message", ex.describe())
    }

    @Test
    fun describeFallsBackToClassNameWhenMessageIsNullOrEmpty() {
        assertEquals("RuntimeException", RuntimeException("").describe())
        assertEquals("RuntimeException", RuntimeException("   ").describe())
        assertEquals("IllegalStateException", IllegalStateException().describe())
        assertEquals("NullPointerException", NullPointerException(null).describe())
    }
}
