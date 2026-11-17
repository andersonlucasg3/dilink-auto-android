package com.dilinkauto.server.data

import android.content.Context

/**
 * Persistence for the home-grid pinning (audit R3-SRP-07).
 *
 * The app grid used to open SharedPreferences and write the pinned set right
 * inside the composable — pinning is data, not rendering. The composable now
 * binds to [load] and calls [toggle].
 */
class PinnedAppsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Currently pinned package names. */
    fun load(): Set<String> = prefs.getStringSet(KEY_PINNED_APPS, emptySet())?.toSet() ?: emptySet()

    /** Flip [packageName]'s pinned state, persist it, and return the new set. */
    fun toggle(packageName: String): Set<String> {
        val current = load()
        val next = if (packageName in current) current - packageName else current + packageName
        prefs.edit().putStringSet(KEY_PINNED_APPS, next).apply()
        return next
    }

    private companion object {
        const val PREFS_NAME = "dilinkauto_pinned"
        const val KEY_PINNED_APPS = "pinned_apps"
    }
}
