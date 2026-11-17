package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #217：切页把 SurfaceView 置 INVISIBLE 后触摸链路整体消失。
 *
 * 这里锁死两条不变量：
 * 1. APP 页必须转发，且 **surface 未就绪时也要转发**——否则 surface 重建窗口
 *    又变成「完全不能操作」；
 * 2. HOME 页一律不转发，磁贴/滚动/搜索框不能被镜像触摸层抢走。
 */
class MirrorTouchPolicyTest {

    @Test
    fun `APP 页 surface 就绪时转发触摸`() {
        assertTrue(MirrorTouchPolicy.shouldForwardTouch(onAppScreen = true, surfaceReady = true))
    }

    @Test
    fun `APP 页 surface 重建期间仍转发触摸`() {
        // 回归钉：若有人给判定加 `&& surfaceReady`，本用例立刻变红。
        assertTrue(MirrorTouchPolicy.shouldForwardTouch(onAppScreen = true, surfaceReady = false))
    }

    @Test
    fun `HOME 页 surface 就绪时也不转发`() {
        assertFalse(MirrorTouchPolicy.shouldForwardTouch(onAppScreen = false, surfaceReady = true))
    }

    @Test
    fun `HOME 页 surface 未就绪时不转发`() {
        assertFalse(MirrorTouchPolicy.shouldForwardTouch(onAppScreen = false, surfaceReady = false))
    }
}
