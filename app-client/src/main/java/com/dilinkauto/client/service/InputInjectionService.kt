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
                activePtrPrev[event.pointerId] = Pair(pixelX, pixelY)
            }
            InputMsg.TOUCH_MOVE -> {
                val prev = activePtrPrev[event.pointerId] ?: return
                // Dispatch incremental segment immediately so the VD gets real-time feedback.
                // A 30ms stroke is short enough not to queue up at 60Hz input rate.
                dispatchSegment(prev.first, prev.second, pixelX, pixelY)
                activePtrPrev[event.pointerId] = Pair(pixelX, pixelY)
            }
            InputMsg.TOUCH_UP -> {
                activePtrPrev.remove(event.pointerId)
                dispatchTap(pixelX, pixelY)
            }
        }
    }

    private fun dispatchSegment(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
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

    private val activePtrPrev = mutableMapOf<Int, Pair<Float, Float>>()

    companion object {
        private const val TAG = "InputInjectionService"

        @Volatile
        var instance: InputInjectionService? = null
            private set
    }
}
