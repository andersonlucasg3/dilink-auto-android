package com.dilinkauto.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶层返回栈（issue #68）。
 *
 * 钉住三条路径：设置 -> 车机 APP -> 返回 = 设置；设置 -> 返回 = 主界面；
 * 主界面栈空时 back() 返回 false（交给 Activity 默认 finish）。
 */
class BackStackTest {

    @Test
    fun `settings then allowlist returns to settings`() {
        val nav = BackStack("main")

        nav.navigate("settings")
        nav.navigate("allowlist")

        assertTrue(nav.back())
        assertEquals("settings", nav.current)
    }

    @Test
    fun `settings returns to main`() {
        val nav = BackStack("main")

        nav.navigate("settings")

        assertTrue(nav.back())
        assertEquals("main", nav.current)
    }

    @Test
    fun `main has empty history and back does nothing`() {
        val nav = BackStack("main")

        assertFalse(nav.back())
        assertEquals("main", nav.current)
    }

    @Test
    fun `reset clears history and changes current`() {
        val nav = BackStack("main")

        nav.navigate("settings")
        nav.reset("main")

        assertFalse(nav.back())
        assertEquals("main", nav.current)
    }

    @Test
    fun `navigating to the current screen does not grow history`() {
        val nav = BackStack("main")

        nav.navigate("main")
        assertFalse(nav.back())
    }
}
