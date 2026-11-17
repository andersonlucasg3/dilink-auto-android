package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #59：横屏 VD 必须在会话期内锁定方向，否则锁竖屏的应用会渲染成居中的竖屏小窗。
 *
 * 实车证据：`VD=2560x1402` 时 `com.bilibili.app.in` 的
 * `mLastNonFullscreenBounds=Rect(920, 51 - 1641, 1351)`（宽 721、高 1300、水平居中）。
 *
 * 用例把「锁方向要发什么命令 / 何时跳过」钉住，注入 shell 不依赖设备。
 */
class VirtualDisplayCreatorRotationTest {

    private class Rig {
        val shells = mutableListOf<String>()
        val logs = mutableListOf<String>()

        val creator = VirtualDisplayCreator(
            displayWidth = 2560,
            displayHeight = 1402,
            dpi = 302,
            execShell = { shells += it },
            execShellOutput = { null },
            log = { logs += it },
            err = { },
        )
    }

    @Test
    fun `锁定横屏下发 lock 0`() {
        val r = Rig()
        r.creator.lockLandscapeRotation(targetDisplayId = 8)

        assertEquals(listOf("wm user-rotation -d 8 lock 0"), r.shells)
    }

    @Test
    fun `display id 未知时不下发任何命令`() {
        val r = Rig()
        r.creator.lockLandscapeRotation(targetDisplayId = -1)

        assertTrue("display id 未知时不该发 wm 命令: ${r.shells}", r.shells.isEmpty())
    }

    @Test
    fun `锁定命令必须是 lock 而不是 free`() {
        // free 会把方向交还给应用，正是 issue #59 的症状来源；这条防止有人「顺手」改回去。
        val r = Rig()
        r.creator.lockLandscapeRotation(targetDisplayId = 19)

        assertTrue(r.shells.single().endsWith("lock 0"))
    }
}
