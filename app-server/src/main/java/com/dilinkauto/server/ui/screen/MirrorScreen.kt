package com.dilinkauto.server.ui.screen

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.server.NativeCarBridge
import com.dilinkauto.server.service.CarConnectionService

/**
 * Mirror content — TextureView for video + touch forwarding.
 * Uses TextureView instead of SurfaceView to avoid z-ordering issues
 * where SurfaceView would punch through the persistent nav bar.
 *
 * The decoder is never stopped during screen navigation. When the TextureView
 * surface is available and the decoder is already running (early start on offscreen
 * surface), we switch the surface with setOutputSurface(). When it's the first
 * time, we start normally. When the surface is destroyed, the decoder stays running
 * — CarConnectionService.handleDisconnect/shutdown handles the final stop.
 */
@Composable
fun MirrorContent(service: CarConnectionService, visible: Boolean = true) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        service.log("[MirrorScreen] TextureView surface available: ${width}x${height}, nativeRunning=${NativeCarBridge.nativeIsRunning()}")
                        val surface = Surface(surfaceTexture)
                        if (NativeCarBridge.nativeIsRunning()) {
                            // Native decoder already running — switch output surface.
                            // Uses AMediaCodec_setOutputSurface — zero frame loss.
                            NativeCarBridge.nativeSetSurface(surface)
                            service.log("[MirrorScreen] Decoder surface switched to TextureView (no restart)")
                        }
                        // If native is not running yet, it will start when connectVideoAndInput
                        // receives the CONFIG frame and creates the decoder.
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        // During normal screen navigation the TextureView is kept alive
                        // (INVISIBLE, not GONE), so this only fires on activity teardown.
                        // The decoder is stopped by handleDisconnect/shutdown — just log.
                        service.log("[MirrorScreen] TextureView surface destroyed")
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                }

                setOnTouchListener { view, event ->
                    service.log("[MirrorScreen] TOUCH: action=${event.actionMasked} x=${event.x/view.width} y=${event.y/view.height}")
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
                            // Batch ALL active pointers into one message (reduces syscalls for multi-touch)
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
                        else -> return@setOnTouchListener false
                    }
                    true
                }
            }
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )
}
