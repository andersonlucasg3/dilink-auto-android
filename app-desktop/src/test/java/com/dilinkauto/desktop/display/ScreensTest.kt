package com.dilinkauto.desktop.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DesktopScreen] / [Screens] 单测。
 *
 * [Screens.list] 依赖真实图形环境：CI 上多为 headless（返回空表），开发机上返回
 * 真实显示器。因此对它只做"不抛异常 + 返回值自洽"的断言，格式相关的事交给
 * 构造数据的用例覆盖。
 */
class ScreensTest {

    @Test
    fun `label 首位是 1 基序号并带分辨率`() {
        val s = DesktopScreen(index = 0, x = 0, y = 0, width = 1920, height = 1080, isPrimary = false)
        assertEquals("#1 1920x1080", s.label)
    }

    @Test
    fun `label 在主屏上追加主标记`() {
        val s = DesktopScreen(index = 1, x = 1920, y = 0, width = 2560, height = 1440, isPrimary = true)
        assertEquals("#2 2560x1440 (主)", s.label)
    }

    @Test
    fun `list 不抛异常且返回值自洽`() {
        val screens = Screens.list()

        // 序号必须与下标一致（UI 用它做稳定标识）
        screens.forEachIndexed { i, screen ->
            assertEquals(i, screen.index)
            assertTrue("分辨率应为正: $screen", screen.width > 0 && screen.height > 0)
        }
        // 主屏至多一块
        assertTrue("主屏不应多于一块", screens.count { it.isPrimary } <= 1)
    }
}
