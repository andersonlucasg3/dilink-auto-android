package com.dilinkauto.client.display

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Fallback for operations that require shell-level access.
 * Used when ActivityOptions.setLaunchDisplayId() is blocked for third-party apps,
 * or when GestureDescription.setDisplayId() doesn't work.
 *
 * All commands run via Runtime.exec() as the app's own UID.
 * This works for virtual displays owned by the app's process.
 */
object AdbBridge {

    private const val TAG = "AdbBridge"

    fun launchApp(displayId: Int, packageName: String): Boolean {
        val cmd = "am start --display $displayId " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "$packageName"
        return exec(cmd, "launchApp($packageName, display=$displayId)")
    }

    fun goBack(displayId: Int): Boolean {
        return exec(
            "input -d $displayId keyevent 4",
            "goBack(display=$displayId)"
        )
    }

    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        return exec(
            "input -d $displayId tap $x $y",
            "tap($x, $y, display=$displayId)"
        )
    }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return exec(
            "input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs",
            "swipe(display=$displayId)"
        )
    }

    private fun exec(command: String, label: String): Boolean {
        return try {
            Log.d(TAG, "$label: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
                Log.w(TAG, "$label failed (exit=$exitCode): $stderr")
            }

            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "$label exception", e)
            false
        }
    }
}
