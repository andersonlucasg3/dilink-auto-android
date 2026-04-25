package com.dilinkauto.client.display

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manages app launching for the car.
 *
 * Currently launches apps on the physical screen. The VideoEncoder's
 * AUTO_MIRROR virtual display captures whatever is on the physical screen
 * and streams it to the car at the car's resolution.
 *
 * Future: Use Shizuku for direct VD-native launches without root/ADB.
 */
class VirtualDisplayManager(
    val displayId: Int,
    val width: Int,
    val height: Int
) {

    fun launchApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                Log.w(TAG, "No launch intent for $packageName")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Launched $packageName on physical screen")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            false
        }
    }

    fun goHome(context: Context) {
        Log.i(TAG, "Go home")
        // Send the user back to the phone's home screen
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun goBack() {
        Log.d(TAG, "Back action sent via bridge")
        InputInjectionServiceBridge.performBack()
    }

    object InputInjectionServiceBridge {
        var performBack: () -> Unit = {}
    }

    companion object {
        private const val TAG = "VirtualDisplayManager"
    }
}
