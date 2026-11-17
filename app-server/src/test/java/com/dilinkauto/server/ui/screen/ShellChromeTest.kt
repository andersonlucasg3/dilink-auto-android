package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #54：时间 / 海拔 / 天气必须常驻，不得随 HOME→APP 切换消失。
 *
 * 判定收敛到 [ShellChrome] 后，签名里没有任何 [Screen] 参数——本测试把这条约束钉死：
 * 只要有人想按页面隐藏 chip，就必须先改这个 API，从而触发本测试。
 */
class ShellChromeTest {

    @Test
    fun `横屏信息 chip 落在左侧常驻栏`() {
        assertEquals(InfoPlacement.LEFT_RAIL, ShellChrome.infoPlacement(isLandscape = true))
    }

    @Test
    fun `竖屏信息 chip 落在顶部信息条`() {
        assertEquals(InfoPlacement.TOP_BAR, ShellChrome.infoPlacement(isLandscape = false))
    }

    @Test
    fun `横屏不再渲染内容区顶部信息条`() {
        // 横屏的 chip 已经在左栏里，再渲染一条顶部横条会重复占高。
        assertFalse(ShellChrome.showTopInfoBar(isLandscape = true, chipsEmpty = false))
        assertFalse(ShellChrome.showTopInfoBar(isLandscape = true, chipsEmpty = true))
    }

    @Test
    fun `竖屏有 chip 时渲染顶部信息条`() {
        assertTrue(ShellChrome.showTopInfoBar(isLandscape = false, chipsEmpty = false))
    }

    @Test
    fun `竖屏无 chip 时不渲染空条`() {
        assertFalse(ShellChrome.showTopInfoBar(isLandscape = false, chipsEmpty = true))
    }
}
