package com.dilinkauto.server.ui.screen

/**
 * 镜像页触摸转发策略（issue #217）。
 *
 * 两条不变量：
 * 1. 只有镜像页（[Screen.APP]）才转发触摸；HOME 页一律放行，磁贴/滚动/搜索框不能
 *    被镜像触摸层抢走。
 * 2. [surfaceReady] **刻意不参与判定**：切页时 SurfaceView 会销毁并重建 surface，
 *    若在这段窗口里停止转发，就会退回 #217 的「切页后完全不能操作」。
 */
internal object MirrorTouchPolicy {

    fun shouldForwardTouch(
        onAppScreen: Boolean,
        @Suppress("UNUSED_PARAMETER") surfaceReady: Boolean
    ): Boolean = onAppScreen
}
