package com.dilinkauto.server.ui.screen

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #217：切页把 SurfaceView 置 INVISIBLE 后触摸链路整体消失。
 *
 * 这里锁死两条不变量：
 * 1. APP 页按下后即使立刻切到 HOME（surface 同时销毁重建），MOVE/UP/CANCEL 仍要
 *    转发到手势结束，否则手机侧会留下按住的「悬空手指」；
 * 2. HOME 页的按下不建立会话、不转发，磁贴/滚动/搜索框不受影响。
 */
class MirrorTouchGateTest {

    @Test
    fun `APP 页按下后切页到 HOME，手势尾段继续转发`() {
        val gate = MirrorTouchGate()
        assertTrue(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = true))
        // 切页到 HOME：MOVE 与 UP 必须继续转发，否则手机侧悬空手指。
        assertTrue(gate.shouldForward(MotionEvent.ACTION_MOVE, onAppScreen = false))
        assertTrue(gate.shouldForward(MotionEvent.ACTION_UP, onAppScreen = false))
        // 手势结束后回到 HOME 语义：新的按下不再建立会话。
        assertFalse(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = false))
    }

    @Test
    fun `取消手势同样结束会话`() {
        val gate = MirrorTouchGate()
        assertTrue(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = true))
        assertTrue(gate.shouldForward(MotionEvent.ACTION_CANCEL, onAppScreen = true))
        assertFalse(gate.shouldForward(MotionEvent.ACTION_MOVE, onAppScreen = true))
    }

    @Test
    fun `HOME 页按下不建立会话`() {
        val gate = MirrorTouchGate()
        assertFalse(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = false))
        assertFalse(gate.shouldForward(MotionEvent.ACTION_MOVE, onAppScreen = false))
        assertFalse(gate.shouldForward(MotionEvent.ACTION_UP, onAppScreen = false))
    }

    @Test
    fun `APP 按下未收到 UP 时残留会话不会吞 HOME 触摸`() {
        val gate = MirrorTouchGate()
        assertTrue(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = true))
        // 窗口失焦/被移除且没有 ACTION_UP：代理会调用 reset() 关闭会话。
        gate.reset()
        assertFalse(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = false))
    }

    @Test
    fun `HOME 按下后切到 APP，MOVE 与 UP 都不转发`() {
        val gate = MirrorTouchGate()
        // HOME 的空区按下不建立会话，切到 APP 后也不能补发尾段。
        assertFalse(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = false))
        assertFalse(gate.shouldForward(MotionEvent.ACTION_MOVE, onAppScreen = true))
        assertFalse(gate.shouldForward(MotionEvent.ACTION_UP, onAppScreen = true))
    }

    @Test
    fun `APP 页多指抬手不结束会话`() {
        val gate = MirrorTouchGate()
        assertTrue(gate.shouldForward(MotionEvent.ACTION_DOWN, onAppScreen = true))
        assertTrue(gate.shouldForward(MotionEvent.ACTION_POINTER_DOWN, onAppScreen = true))
        // 一根手指抬起后会话仍保持，剩余指针继续转发（此时已切到 HOME 也一样）。
        assertTrue(gate.shouldForward(MotionEvent.ACTION_POINTER_UP, onAppScreen = true))
        assertTrue(gate.shouldForward(MotionEvent.ACTION_MOVE, onAppScreen = false))
        assertTrue(gate.shouldForward(MotionEvent.ACTION_UP, onAppScreen = false))
    }
}
