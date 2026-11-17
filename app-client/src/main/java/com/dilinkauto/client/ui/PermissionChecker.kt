package com.dilinkauto.client.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager

/**
 * Runtime permission checks for the phone UI's onboarding and settings screens.
 *
 * Extracted from OnboardingScreen and SettingsScreen, which had each check
 * written inline three times (initial read, periodic re-check, and in
 * OnboardingScreen's `pollPermission` switch). Centralizing here means a
 * change to the check logic (e.g. a new ROM quirk) lands in one place.
 *
 * All functions are plain Context receivers so they can be called from any
 * Composable or coroutine scope without threading state through.
 */
object PermissionChecker {
    /** MANAGE_EXTERNAL_STORAGE on API 30+; trivially true below (permission doesn't exist). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    /** Battery optimization exemption. */
    fun hasBatteryExemption(context: Context, pkg: String): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(pkg)
    }

    /**
     * Whether our accessibility service is enabled. SettingsScreen and
     * OnboardingScreen both query this; OnboardingScreen polled it again
     * inside `pollPermission`, hence the third copy.
     */
    fun hasAccessibility(context: Context, pkg: String): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == pkg }
    }
}
