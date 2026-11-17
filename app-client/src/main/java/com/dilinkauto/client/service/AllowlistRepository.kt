package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * Encapsulates read/write access and update notifications for the car app allowlist.
 *
 * Centralizes the SharedPreferences storage (`dilinkauto_allowlist`), first-run
 * map seeding, and live notification to [ConnectionService].
 */
class AllowlistRepository internal constructor(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    private val seeder: AllowlistSeeder = AllowlistSeeder(KEY_PACKAGES, KEY_CONFIGURED),
    private val isServiceRunning: () -> Boolean = { ConnectionService.serviceState.value != SessionState.IDLE }
) {
    /**
     * Reads the set of allowed packages, or `null` if unconfigured (allow all).
     * If [seedIfUnconfigured] is true and [pm] is provided, seeds defaults on first run.
     */
    fun getAllowedPackages(pm: PackageManager? = null, seedIfUnconfigured: Boolean = false): Set<String>? {
        if (seedIfUnconfigured && pm != null && !isConfigured()) {
            seeder.seedIfNeeded(pm, prefs)
        }
        return prefs.getStringSet(KEY_PACKAGES, null)
    }

    /**
     * Whether the user or seeder has configured the allowlist at least once.
     */
    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    /**
     * Persists the selected packages, marks configured, and optionally notifies
     * [ConnectionService] if a session is currently active.
     */
    fun saveAllowedPackages(packages: Set<String>, notifyService: Boolean = true) {
        prefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()

        if (notifyService) {
            notifyServiceUpdated()
        }
    }

    /**
     * Notifies [ConnectionService] that the allowlist has changed so the car grid
     * updates live, provided the service is running.
     */
    fun notifyServiceUpdated() {
        if (isServiceRunning()) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_ALLOWLIST_UPDATED
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Ignore if context cannot start service (e.g. background restrictions)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "dilinkauto_allowlist"
        const val KEY_PACKAGES = "allowed_packages"
        const val KEY_CONFIGURED = "allowlist_configured"
        const val ACTION_ALLOWLIST_UPDATED = "com.dilinkauto.client.ALLOWLIST_UPDATED"
    }
}
