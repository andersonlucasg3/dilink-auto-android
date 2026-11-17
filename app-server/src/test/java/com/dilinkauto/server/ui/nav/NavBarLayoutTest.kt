package com.dilinkauto.server.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航栏动作集合的回归钉子（issue #152-PA）。
 *
 * UI 层是 Compose，本模块没有 Compose 测试基建；把「有哪些键 / 什么顺序 / 什么时候可用」
 * 收敛到 [NavBarLayout] 后，这几条约束就能像 `ShellChromeTest` 一样在 JVM 上钉死：
 * 谁想把「最近」键删掉、改顺序，或让它绕开连接状态闸门，都必须先改这个 API 并让本测试红。
 */
class NavBarLayoutTest {

    @Test
    fun `横屏左栏顺序为 Home Back Recent Eject`() {
        assertEquals(
            listOf(NavAction.Home, NavAction.Back, NavAction.Recent, NavAction.Eject),
            NavBarLayout.actions(reverse = false)
        )
    }

    @Test
    fun `竖屏底栏顺序是左栏的严格镜像`() {
        val rail = NavBarLayout.actions(reverse = false)
        assertEquals(rail.reversed(), NavBarLayout.actions(reverse = true))
    }

    @Test
    fun `导航栏共四个键且包含且仅包含一个最近键`() {
        val actions = NavBarLayout.actions(reverse = false)
        assertEquals(4, actions.size)
        assertEquals(1, actions.count { it == NavAction.Recent })
        // 四个键互不重复：漏键或多键都会让这里的去重数变化。
        assertEquals(actions.size, actions.toSet().size)
    }

    @Test
    fun `横竖屏动作集合相同只有顺序差异`() {
        assertEquals(
            NavBarLayout.actions(reverse = false).toSet(),
            NavBarLayout.actions(reverse = true).toSet()
        )
    }

    @Test
    fun `未连接时只有最近键不可用`() {
        assertFalse(NavBarLayout.isEnabled(NavAction.Recent, connected = false))
        // Home / Back 维持 issue #152 之前的行为，Eject 必须能在重连中中止会话。
        assertTrue(NavBarLayout.isEnabled(NavAction.Home, connected = false))
        assertTrue(NavBarLayout.isEnabled(NavAction.Back, connected = false))
        assertTrue(NavBarLayout.isEnabled(NavAction.Eject, connected = false))
    }

    @Test
    fun `已连接时四个键都可用`() {
        for (action in NavBarLayout.actions(reverse = false)) {
            assertTrue("$action 应在已连接时可用", NavBarLayout.isEnabled(action, connected = true))
        }
    }
}
