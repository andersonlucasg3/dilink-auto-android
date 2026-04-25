package com.dilinkauto.server.ui.screen

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.server.service.CarConnectionService

/**
 * Mirror content — TextureView for video + touch forwarding.
 * Uses TextureView instead of SurfaceView to avoid z-ordering issues
 * where SurfaceView would punch through the persistent nav bar.
 */
@Composable
fun MirrorContent(service: CarConnectionService) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        service.log("[MirrorScreen] TextureView surface available: ${width}x${height}, decoder.isRunning=${service.videoDecoder.isRunning}")
                        val surface = Surface(surfaceTexture)
                        // Restart decoder with the real TextureView surface.
                        // The decoder may already be running on an offscreen surface
                        // (started early in handleVideoFrame to avoid frame loss).
                        service.videoDecoder.stop()
                        service.log("[MirrorScreen] Decoder stopped, restarting with real surface")
                        service.videoDecoder.start(
                            surface,
                            service.vdWidth,
                            service.vdHeight
                        )
                        service.releaseOffscreenSurface()
                        service.log("[MirrorScreen] Decoder restarted on TextureView surface")
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        service.log("[MirrorScreen] TextureView surface destroyed, stopping decoder")
                        service.videoDecoder.stop()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                }

                setOnTouchListener { view, event ->
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )
}
