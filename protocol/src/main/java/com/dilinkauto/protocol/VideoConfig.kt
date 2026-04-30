package com.dilinkauto.protocol

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    const val TARGET_FPS = 60
    const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 16ms at 60fps
    const val VIRTUAL_DISPLAY_DPI = 480  // phone DPI used for VD creation and touch mapping
    const val TARGET_SW_DP = 600  // smallest-width dp for VD size calculation (normal mode)

    // ─── Desktop mode (Samsung DeX, Android Desktop) ───

    /**
     * DPI used when desktop mode (Samsung DeX, Android Desktop) is active.
     *
     * Samsung DeX creates external displays at 160 dpi (mdpi) natively — this is
     * the density its system UI (taskbar, window decorations) is designed for.
     * At higher DPIs the DeX taskbar renders at a smaller pixel size, making it
     * too small to see or touch on the car's viewport.
     *
     * Using mdpi makes pixel-based DeX system UI elements proportionally larger
     * (VD has fewer pixels → same pixel size takes up more of the VD → upscaled
     * to fill car screen = larger on car). dp-based app UI is unaffected because
     * the dp-to-physical-size formula cancels out DPI.
     */
    const val DESKTOP_MODE_DPI = 160

    /**
     * Smallest-width dp for the VD when desktop mode is active.
     * Lower than normal TARGET_SW_DP so dp-based UI elements render larger
     * on the car screen (physical size = dp / SW_DP × physical screen height).
     * At 440, a 48dp element is ~1.15 cm on the BYD DiLink display (vs ~0.84
     * cm at 600, a 37% increase).
     */
    const val DESKTOP_MODE_TARGET_SW_DP = 440

    /**
     * Returns the DPI that should be used for the VirtualDisplay.
     */
    fun getVirtualDisplayDpi(context: Context): Int {
        return if (isDesktopMode(context)) DESKTOP_MODE_DPI else VIRTUAL_DISPLAY_DPI
    }

    /**
     * Returns the smallest-width dp for VD size calculation.
     * Uses a lower value in desktop mode so UI elements render larger.
     */
    fun getTargetSwDp(context: Context): Int {
        return if (isDesktopMode(context)) DESKTOP_MODE_TARGET_SW_DP else TARGET_SW_DP
    }

    /**
     * Returns the smallest-width dp based on the VD DPI received from the phone.
     * Used on the car side, which doesn't have direct access to the phone's
     * desktop mode detection — it infers desktop mode from the handshake DPI.
     */
    fun getTargetSwDpForDpi(vdDpi: Int): Int {
        return if (vdDpi == DESKTOP_MODE_DPI) DESKTOP_MODE_TARGET_SW_DP else TARGET_SW_DP
    }

    // ─── Desktop mode detection ───

    /**
     * Detects whether the device is currently in desktop mode.
     * Tries multiple detection strategies in order:
     * 1. UiModeManager.currentModeType == UI_MODE_TYPE_DESK (standard Android API)
     * 2. Samsung SemDesktopModeManager (reflection, One UI 3+)
     * 3. Samsung DeX system property (ro.boot.dexstatus or persist.sys.dex_status)
     * 4. Display-based: checks for TYPE_EXTERNAL displays with desktop flags
     */
    fun isDesktopMode(context: Context): Boolean {
        // Strategy 1: UiModeManager (Android 8.0+, covers native Desktop Mode and DeX)
        try {
            val um = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (um?.currentModeType == Configuration.UI_MODE_TYPE_DESK) return true
        } catch (_: Exception) {}

        // Strategy 2: Samsung SemDesktopModeManager (One UI 3.0+, Android 11+)
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            try {
                val cls = Class.forName("com.samsung.android.desktopmode.SemDesktopModeManager")
                val getInstance = cls.getDeclaredMethod("getInstance", Context::class.java)
                val instance = getInstance.invoke(null, context)
                val getDesktopModeState = cls.getDeclaredMethod("getDesktopModeState")
                val state = getDesktopModeState.invoke(instance)
                // SemDesktopModeState has an enum with DEX_ON, DEX_OFF, etc.
                // Checking toString() covers historical and future enum names.
                val stateStr = state.toString()
                if (stateStr.contains("ON") || stateStr.contains("CONNECTED") ||
                    stateStr.contains("DEX") && !stateStr.contains("OFF")) {
                    return true
                }
            } catch (_: Exception) {}
        }

        // Strategy 3: System properties (Samsung devices)
        try {
            val dexStatus = getSystemProperty("persist.sys.dex_status")
            if (dexStatus == "1" || dexStatus == "true") return true
            val bootDex = getSystemProperty("ro.boot.dexstatus")
            if (bootDex == "1" || bootDex == "true") return true
        } catch (_: Exception) {}

        return false
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getDeclaredMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (_: Exception) { null }
    }
}
