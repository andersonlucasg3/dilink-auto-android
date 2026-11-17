package com.dilinkauto.server.ui.screen

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.server.service.CarConnectionService

/**
 * Mirror content — SurfaceView for video + touch forwarding.
 *
 * SurfaceView (not TextureView) so the decoder's output goes through a hardware
 * overlay instead of an extra per-frame GL composite pass. On the Adreno 505 the
 * TextureView composite cost (~2-5ms/frame at 1280x800) is a meaningful slice of
 * the 42ms budget at 24fps; SurfaceView bypasses it entirely.
 *
 * Tradeoff: SurfaceView destroys its surface when the view goes INVISIBLE, where
 * TextureView kept it alive. Navigation HOME<->APP toggles `visible`, so
 * surfaceCreated/surfaceDestroyed fire on each switch. The decoder is NOT stopped
 * on surfaceDestroyed — it stays running, and [VideoDecoder.invalidateSurface]
 * gates the render flag off so frames aren't dropped to a destroyed surface.
 * surfaceCreated calls switchSurface (setOutputSurface) to re-attach, restoring
 * rendering with zero keyframe loss. handleDisconnect/shutdown owns the final
 * decoder stop.
 *
 * issue #217: the touch entry point is deliberately NOT the SurfaceView. An
 * INVISIBLE View does not take part in hit testing, so binding the touch listener
 * to the SurfaceView lost touch for the whole HOME<->APP switch (exactly the
 * window in which the surface is also destroyed and rebuilt). The SurfaceView and
 * a transparent touch proxy now live in a FrameLayout: the surface keeps its old
 * visibility/lifecycle, while the proxy always receives MotionEvents. The proxy
 * only consumes them on the APP screen ([MirrorTouchPolicy]); on HOME it returns
 * false and lets Compose's tiles/scroll/search handle the gesture.
 *
 * Both surface callbacks are deliberately thin (audit S-M3): they forward to
 * `CarConnectionService.onMirrorSurfaceCreated/Destroyed`, which hand the work
 * to a service-side single-thread executor — MediaCodec create/configure must
 * not run on the main thread that delivers these callbacks.
 *
 * The persistent nav bar is a sibling (not overlapping) this composable in both
 * landscape and portrait layouts (MainActivity CarShell), so SurfaceView's
 * default z-order — surface below the window hierarchy — renders the nav bar
 * above the video correctly.
 */
@Composable
fun MirrorContent(service: CarConnectionService, visible: Boolean = true) {
    // update 与触摸回调之间共享的运行时状态；两者都在主线程，不需要额外同步。
    val touchState = remember { MirrorTouchState() }

    AndroidView(
        factory = { context -> createMirrorContainer(context, service, touchState) },
        update = { container ->
            touchState.visible = visible
            // 只切 SurfaceView 的可见性；容器与透明触摸代理始终在场，触摸链路不断。
            surfaceViewOf(container).visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = Modifier
            .fillMaxSize()
    )
}

/**
 * 触摸代理层与 Compose 之间共享的状态。
 *
 * `visible` 由 [AndroidView] 的 update 写入（APP 页为 true），触摸回调只读；
 * `surfaceReady` 只用于诊断/策略签名，**不参与转发判定**（issue #217）。
 */
private class MirrorTouchState {
    var visible: Boolean = false
    var surfaceReady: Boolean = false
}

/** 容器约定：0 号子 View 是 SurfaceView，1 号是透明触摸代理（见 [createMirrorContainer]）。 */
private fun surfaceViewOf(container: FrameLayout): SurfaceView =
    container.getChildAt(0) as SurfaceView

private fun createMirrorContainer(
    context: Context,
    service: CarConnectionService,
    touchState: MirrorTouchState
): FrameLayout {
    val surfaceView = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                touchState.surfaceReady = true
                // Deliberately thin (audit S-M3): the callback fires on the
                // main thread and MediaCodec create/configure is expensive
                // enough to be visible as jank on the 8x A53 head unit.
                // The service hands the Surface to its single decoder
                // thread, which owns start/switchSurface.
                service.onMirrorSurfaceCreated(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                // No-op — the decoder renders at fixed vdWidth x vdHeight;
                // the SurfaceView scales the buffer to the view size.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                touchState.surfaceReady = false
                // Surface is gone (view went INVISIBLE or activity teardown).
                // Do NOT stop the decoder — navigation between HOME and APP
                // toggles visibility, and stopping/restarting the codec would
                // drop the keyframe cache and require a fresh IDR. Just gate
                // the render flag off; surfaceCreated will re-attach a new
                // surface via setOutputSurface.
                service.onMirrorSurfaceDestroyed()
            }
        })
    }

    // issue #217：透明触摸代理，始终覆盖在 SurfaceView 之上。SurfaceView 因切页变
    // INVISIBLE 时它仍然接收 MotionEvent，因此 surface 重建期间触摸也不会断。
    val touchProxy = View(context).apply {
        setOnTouchListener { view, event ->
            if (!MirrorTouchPolicy.shouldForwardTouch(touchState.visible, touchState.surfaceReady)) {
                // HOME 页放行：事件继续交给 Compose 的磁贴/滚动/搜索框，不吞事件。
                return@setOnTouchListener false
            }
            forwardTouch(view, event, service)
        }
    }

    return FrameLayout(context).apply {
        addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            touchProxy,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }
}

/**
 * 把 View 层的 MotionEvent 归一化后交给 [CarConnectionService]。
 * 返回 true 表示已消费；未知 action 返回 false，不吞事件。
 */
private fun forwardTouch(
    view: View,
    event: MotionEvent,
    service: CarConnectionService
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val idx = event.actionIndex
            service.sendTouchEvent(TouchEvent(
                action = InputMsg.TOUCH_DOWN,
                pointerId = event.getPointerId(idx),
                x = event.getX(idx) / view.width,
                y = event.getY(idx) / view.height,
                pressure = event.getPressure(idx),
                timestamp = event.eventTime
            ))
        }
        MotionEvent.ACTION_MOVE -> {
            // BatchALL active pointers into one message (reduces syscalls for multi-touch)
            val pointers = (0 until event.pointerCount).map { i ->
                TouchEvent(
                    action = InputMsg.TOUCH_MOVE,
                    pointerId = event.getPointerId(i),
                    x = event.getX(i) / view.width,
                    y = event.getY(i) / view.height,
                    pressure = event.getPressure(i),
                    timestamp = event.eventTime
                )
            }
            service.sendTouchBatch(pointers)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val idx = event.actionIndex
            service.sendTouchEvent(TouchEvent(
                action = InputMsg.TOUCH_UP,
                pointerId = event.getPointerId(idx),
                x = event.getX(idx) / view.width,
                y = event.getY(idx) / view.height,
                pressure = event.getPressure(idx),
                timestamp = event.eventTime
            ))
        }
        MotionEvent.ACTION_CANCEL -> {
            // Gesture cancelled — release ALL pointers to prevent ghost fingers
            for (i in 0 until event.pointerCount) {
                service.sendTouchEvent(TouchEvent(
                    action = InputMsg.TOUCH_UP,
                    pointerId = event.getPointerId(i),
                    x = event.getX(i) / view.width,
                    y = event.getY(i) / view.height,
                    pressure = 0f,
                    timestamp = event.eventTime
                ))
            }
        }
        else -> return false
    }
    return true
}
