package com.dilinkauto.client.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dilinkauto.client.FileLog

/**
 * System-settings intents used by both the onboarding flow and the settings
 * menu (audit R3-DRY-07 / R3-SRP-09): the Intent construction was duplicated
 * between MainActivity and OnboardingScreen.
 *
 * "Is it already granted / is the SDK new enough" guards stay at the call
 * site; these helpers only launch the intent and report whether it launched.
 */
object PermissionIntents {

    /** Open "All files access" (API 30+). Returns false when not applicable. */
    fun openAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            true
        } catch (e: Exception) {
            FileLog.w("PermissionIntents", "All-files access request failed: ${e.message}")
            false
        }
    }

    /** Open the battery-optimization exemption request for this package. */
    fun openBatteryExemption(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            )
            true
        } catch (e: Exception) {
            FileLog.w("PermissionIntents", "Battery exemption request failed: ${e.message}")
            false
        }
    }

    /** Open the accessibility settings list. */
    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        } catch (e: Exception) {
            FileLog.w("PermissionIntents", "Accessibility settings request failed: ${e.message}")
            false
        }
    }

    /** Open developer options, falling back to the raw settings action. */
    fun openDeveloperOptions(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
                true
            } catch (e: Exception) {
                FileLog.w("PermissionIntents", "Developer options request failed: ${e.message}")
                false
            }
        }
    }
}
