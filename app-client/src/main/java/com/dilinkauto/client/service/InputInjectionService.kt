package com.dilinkauto.client.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service originally designed for virtual-display input injection.
 *
 * Touch injection itself happens in the VD server process (running as shell UID)
 * over dedicated input port 9639. The AccessibilityService touch injection path
 * is obsolete; this service is retained only for system manifest/setting backwards compatibility.
 */
@Deprecated("Touch injection is handled by VD server shell UID. InputInjectionService is retained only for system manifest backwards compatibility.")
class InputInjectionService : AccessibilityService() {

    // Virtual display targeting — retained for binary/service compatibility
    @Volatile private var virtualDisplayId: Int = -1
    @Volatile private var vdWidth: Int = 0
    @Volatile private var vdHeight: Int = 0

    @Deprecated("Virtual display metadata is no longer required by AccessibilityService.")
    fun setVirtualDisplay(displayId: Int, width: Int, height: Int) {
        virtualDisplayId = displayId
        vdWidth = width
        vdHeight = height
        Log.i(TAG, "Virtual display set (deprecated): id=$displayId ${width}x${height}")
    }

    @Deprecated("Virtual display metadata is no longer required by AccessibilityService.")
    fun clearVirtualDisplay() {
        virtualDisplayId = -1
        vdWidth = 0
        vdHeight = 0
        Log.i(TAG, "Virtual display cleared (deprecated)")
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

    companion object {
        private const val TAG = "InputInjectionService"

        @Volatile
        var instance: InputInjectionService? = null
            private set
    }
}
