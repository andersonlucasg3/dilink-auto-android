package com.dilinkauto.server.ui.screen

import android.view.MotionEvent

/**
 * 镜像页触摸转发状态机（issue #217）。
 *
 * 旧实现把 OnTouchListener 挂在 SurfaceView 上，切页置 INVISIBLE 后触摸目标消失，
 * 车机在 HOME<->APP 与 surface 重建期间完全收不到事件。现在由覆盖在 SurfaceView 上的
 * 透明代理接收事件，本状态机决定哪些事件交给手机：
 *
 * - 只有 APP 页的 ACTION_DOWN 才建立触摸会话；
 * - 会话建立后，POINTER_DOWN / MOVE / POINTER_UP / UP / CANCEL 一律继续转发，
 *   **与当前页面无关**：用户按下的瞬间切页时，尾段仍要送到手机，否则手机侧会留下
 *   按住的「悬空手指」（旧实现里 SurfaceView 隐藏也不会取消已建立的触摸目标，
 *   尾段照常转发，行为与本状态机一致）；
 * - HOME 页控件之间的空白区域按下不建立会话，也不消费事件。磁贴/滚动/搜索框等
 *   控件本身由 Compose 命中测试优先处理，事件不会到达代理。
 *
 * 选择「按下后继续转发」而不是「切页时主动 flush」：flush 会在用户仍按住时提前
 * 结束手机侧手势，还需要额外记录所有活动指针；会话式转发与旧实现语义一致，改动面最小。
 */
internal class MirrorTouchGate {

    private var pressed = false

    fun shouldForward(action: Int, onAppScreen: Boolean): Boolean = when (action) {
        MotionEvent.ACTION_DOWN -> {
            // 只在按下时看页面：APP 页才建立会话；HOME 页不接管。
            pressed = onAppScreen
            pressed
        }
        // 手势已经建立；多指按下/抬起、移动都不改变会话状态。
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_POINTER_UP -> pressed
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            val forward = pressed
            pressed = false
            forward
        }
        else -> false
    }

    /**
     * 关闭当前会话。
     *
     * 窗口失焦或被移除时不会再有 ACTION_UP/CANCEL 送达，必须主动复位，
     * 否则残留的 pressed 会让下一次 APP 页按下在手机侧多出一个未配对的 DOWN。
     */
    fun reset() {
        pressed = false
    }
}
