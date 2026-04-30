package com.dilinkauto.protocol

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    const val TARGET_FPS = 60
    const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 16ms at 60fps
    const val VIRTUAL_DISPLAY_DPI = 480  // phone DPI used for VD creation and touch mapping
    const val TARGET_SW_DP = 600  // smallest-width dp for VD size calculation

    /**
     * DPI used when desktop mode (Samsung DeX, Android Desktop) is active.
     * DeX renders system UI at desktop densities (~160-284 dpi). At the phone's
     * native 480 dpi the system UI — especially the navigation bar — becomes too
     * small to see or touch on the car's viewport. tvdpi (213) is a standard
     * Android density bucket used by ~10" tablets, matching the car display.
     */
    const val DESKTOP_MODE_DPI = 213

    /**
     * Returns the DPI that should be used for the VirtualDisplay.
     * When desktop mode (Samsung DeX, Android 16 Desktop Mode) is active,
     * uses a lower density so desktop system UI renders at a touchable size
     * on the car display. Otherwise falls back to the phone's native DPI.
     */
    fun getVirtualDisplayDpi(context: Context): Int {
        return if (isDesktopMode(context)) DESKTOP_MODE_DPI else VIRTUAL_DISPLAY_DPI
    }

    /**
     * Detects whether the device is currently in desktop mode.
     * Covers Samsung DeX (all One UI versions) and native Android Desktop Mode (Android 16+).
     */
    fun isDesktopMode(context: Context): Boolean {
        val um = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        return um.currentModeType == Configuration.UI_MODE_TYPE_DESK
    }
}
