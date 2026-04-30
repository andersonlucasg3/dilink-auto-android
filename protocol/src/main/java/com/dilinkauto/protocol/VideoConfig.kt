package com.dilinkauto.protocol

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    private const val TAG = "VideoConfig"
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
     * Diagnostic log callback — set by the phone to FileLog and by the car to
     * carLogSend so all desktop-mode detection details reach the shared log file.
     * Falls back to Log.d if not set.
     */
    var diagLog: ((String) -> Unit)? = null

    private fun diag(msg: String) {
        diagLog?.invoke(msg) ?: Log.d(TAG, msg)
    }

    private fun diagWarn(msg: String) {
        diagLog?.invoke("WARN: $msg") ?: Log.w(TAG, msg)
    }

    private fun diagInfo(msg: String) {
        diagLog?.invoke(msg) ?: Log.i(TAG, msg)
    }

    /**
     * Returns the DPI that should be used for the VirtualDisplay.
     */
    fun getVirtualDisplayDpi(context: Context): Int {
        val desktop = isDesktopMode(context)
        diagInfo("getVirtualDisplayDpi: desktopMode=$desktop → dpi=${if (desktop) DESKTOP_MODE_DPI else VIRTUAL_DISPLAY_DPI}")
        return if (desktop) DESKTOP_MODE_DPI else VIRTUAL_DISPLAY_DPI
    }

    /**
     * Returns the smallest-width dp for VD size calculation.
     */
    fun getTargetSwDp(context: Context): Int {
        val desktop = isDesktopMode(context)
        return if (desktop) DESKTOP_MODE_TARGET_SW_DP else TARGET_SW_DP
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
     * Detects whether the device is in desktop mode (Samsung DeX, Android Desktop).
     * Every strategy and its result is logged through [diagLog] so failures are
     * visible in the shared log file (FileLog on phone, carLogSend on car).
     */
    fun isDesktopMode(context: Context): Boolean {
        // Strategy 1: UiModeManager (Android 8.0+)
        try {
            val um = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val modeType = um?.currentModeType
            diag("UiModeManager.currentModeType=$modeType (DESK=${Configuration.UI_MODE_TYPE_DESK})")
            if (modeType == Configuration.UI_MODE_TYPE_DESK) {
                diagInfo("Desktop mode detected: UiModeManager")
                return true
            }
        } catch (e: Exception) {
            diagWarn("UiModeManager failed: ${e.message}")
        }

        // Strategy 2: DisplayManager — non-default displays with DeX/Desktop names
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.displays?.forEach { display ->
                val name = display.name ?: ""
                if (display.displayId != android.view.Display.DEFAULT_DISPLAY) {
                    diag("Display id=${display.displayId} name='$name' flags=0x${display.flags.toString(16)}")
                    if (name.contains("DeX", ignoreCase = true) || name.contains("Desktop", ignoreCase = true)) {
                        diagInfo("Desktop mode detected: display name='$name'")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            diagWarn("DisplayManager failed: ${e.message}")
        }

        // Strategy 3: Samsung-specific detection
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            diag("Samsung device — trying DeX APIs")

            // 3a: SemDesktopModeManager.isDesktopMode() boolean (One UI 4+)
            try {
                val cls = Class.forName("com.samsung.android.desktopmode.SemDesktopModeManager")
                val getInstance = cls.getDeclaredMethod("getInstance", Context::class.java)
                val instance = getInstance.invoke(null, context)

                try {
                    val isDm = cls.getDeclaredMethod("isDesktopMode")
                    val result = isDm.invoke(instance) as? Boolean
                    diag("SemDesktopModeManager.isDesktopMode()=$result")
                    if (result == true) {
                        diagInfo("Desktop mode detected: SemDesktopModeManager.isDesktopMode()")
                        return true
                    }
                } catch (_: NoSuchMethodException) {
                    diag("SemDesktopModeManager.isDesktopMode() not found")
                }

                // 3b: getDesktopModeState() enum
                try {
                    val getState = cls.getDeclaredMethod("getDesktopModeState")
                    val state = getState.invoke(instance)
                    val stateStr = state.toString()
                    val stateOrdinal = try {
                        state.javaClass.getMethod("ordinal").invoke(state) as Int
                    } catch (_: Exception) { -1 }
                    val stateName = try {
                        state.javaClass.getMethod("name").invoke(state) as String
                    } catch (_: Exception) { "" }

                    diag("SemDesktopModeManager state=$stateStr name=$stateName ordinal=$stateOrdinal")

                    val lower = stateStr.lowercase()
                    if (lower.contains("dex") && !lower.contains("off") ||
                        lower.contains("desktop") && !lower.contains("disab") ||
                        lower == "enabled" || lower == "on" ||
                        stateName.contains("DEX", ignoreCase = true) ||
                        (stateOrdinal > 0 && stateName.isNotEmpty())) {
                        diagInfo("Desktop mode detected: SemDesktopModeManager state=$stateStr")
                        return true
                    }
                } catch (e: Exception) {
                    diagWarn("getDesktopModeState() failed: ${e.message}")
                }
            } catch (e: Exception) {
                diagWarn("SemDesktopModeManager not available: ${e.message}")
            }

            // 3c: System properties
            val dexProps = listOf(
                "persist.sys.dex_status", "ro.boot.dexstatus",
                "sys.desktopmode", "sys.samsung.desktop",
                "persist.sys.desktop_mode", "sys.debug.dex"
            )
            for (prop in dexProps) {
                try {
                    val value = getSystemProperty(prop)
                    if (value != null) diag("Property $prop=$value")
                    if (value == "1" || value.equals("true", ignoreCase = true)) {
                        diagInfo("Desktop mode detected: property $prop=$value")
                        return true
                    }
                } catch (_: Exception) {}
            }

            // 3d: Settings.System
            val sysKeys = listOf("desktop_mode_enabled", "dex_mode", "samsung_desktop_mode")
            for (key in sysKeys) {
                try {
                    val value = android.provider.Settings.System.getInt(context.contentResolver, key, -1)
                    diag("Settings.System $key=$value")
                    if (value == 1) {
                        diagInfo("Desktop mode detected: Settings.System $key=$value")
                        return true
                    }
                } catch (_: Exception) {}
            }

            // 3e: Settings.Global
            val globalKeys = listOf("desktop_mode", "dex_mode_enabled")
            for (key in globalKeys) {
                try {
                    val value = android.provider.Settings.Global.getInt(context.contentResolver, key, -1)
                    diag("Settings.Global $key=$value")
                    if (value == 1) {
                        diagInfo("Desktop mode detected: Settings.Global $key=$value")
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        diag("Desktop mode NOT detected — using normal VD params")
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
