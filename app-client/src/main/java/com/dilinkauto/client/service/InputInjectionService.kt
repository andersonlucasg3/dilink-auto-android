package com.dilinkauto.client.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent

/**
 * Accessibility service that injects touch events received from the car.
 * Supports targeting a specific virtual display (API 30+) or the physical screen.
 *
 * The user must manually enable this service in:
 * Settings → Accessibility → DiLink Auto
 */
class InputInjectionService : AccessibilityService() {

    private val displayMetrics by lazy { resources.displayMetrics }

    // Virtual display targeting — set by ConnectionService when VD is created
    private var virtualDisplayId: Int = -1
    private var vdWidth: Int = 0
    private var vdHeight: Int = 0

    fun setVirtualDisplay(displayId: Int, width: Int, height: Int) {
        virtualDisplayId = displayId
        vdWidth = width
        vdHeight = height
        Log.i(TAG, "Virtual display set: id=$displayId ${width}x${height}")
    }

    fun clearVirtualDisplay() {
        virtualDisplayId = -1
        vdWidth = 0
        vdHeight = 0
        activePaths.clear()
        Log.i(TAG, "Virtual display cleared")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Injects a touch event received from the car display.
     * Coordinates are normalized (0.0-1.0), mapped to virtual display or phone screen pixels.
     */
    fun injectTouch(event: TouchEvent) {
        // Denormalize to VD dimensions if available, otherwise phone screen
        val targetWidth = if (virtualDisplayId != -1) vdWidth else displayMetrics.widthPixels
        val targetHeight = if (virtualDisplayId != -1) vdHeight else displayMetrics.heightPixels
        val pixelX = event.x * targetWidth
        val pixelY = event.y * targetHeight

        when (event.action) {
            InputMsg.TOUCH_DOWN -> {
                // Release any stuck pointers from an interrupted previous session.
                val stuck = activePaths.keys.filter { it != event.pointerId }.toList()
                for (id in stuck) {
                    dispatchTap(activePaths[id]!!.first().x, activePaths[id]!!.first().y)
                }
                activePaths.clear()
                activePaths[event.pointerId] = mutableListOf(
                    Point(pixelX, pixelY, 0L)
                )
            }
            InputMsg.TOUCH_MOVE -> {
                val points = activePaths[event.pointerId] ?: return
                points.add(Point(pixelX, pixelY, (points.size * 16L).coerceAtLeast(1)))
            }
            InputMsg.TOUCH_UP -> {
                val points = activePaths.remove(event.pointerId)
                if (points == null || points.size < 2) {
                    dispatchTap(pixelX, pixelY)
                } else {
                    points.add(Point(pixelX, pixelY, (points.size * 16L).coerceAtLeast(1)))
                    dispatchSwipe(points)
                }
            }
        }
    }

    private fun dispatchSwipe(points: List<Point>) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
        val totalDuration = points.last().elapsedMs.coerceIn(1, 5000)
        val stroke = GestureDescription.StrokeDescription(path, 0, totalDuration)
        val gesture = buildGesture(stroke)
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchTap(x: Float, y: Float) {
        // Accessibility gestures require a non-zero-length path.
        // A 1px micro-swipe is indistinguishable from a tap at car-display scale.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = buildGesture(stroke)
        dispatchGesture(gesture, null, null)
    }

    /**
     * Builds a GestureDescription, targeting the virtual display if available (API 30+).
     */
    private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder().addStroke(stroke)

        if (virtualDisplayId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(virtualDisplayId)
        }

        return builder.build()
    }

    private data class Point(val x: Float, val y: Float, val elapsedMs: Long)
    private val activePaths = mutableMapOf<Int, MutableList<Point>>()

    companion object {
        private const val TAG = "InputInjectionService"

        @Volatile
        var instance: InputInjectionService? = null
            private set
    }
}
