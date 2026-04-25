package com.dilinkauto.server.ui.nav

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf

/**
 * Tracks recently launched apps for the nav bar.
 * Persists to SharedPreferences so recent apps survive app restarts.
 */
class RecentAppsState(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)

    private val _recentApps = mutableStateListOf<String>()
    val recentApps: List<String> get() = _recentApps

    init {
        val saved = prefs.getString(KEY_RECENT, null)
        if (!saved.isNullOrEmpty()) {
            _recentApps.addAll(saved.split(",").filter { it.isNotEmpty() }.take(MAX_RECENT))
        }
    }

    fun onAppLaunched(packageName: String) {
        _recentApps.remove(packageName)
        _recentApps.add(0, packageName)
        if (_recentApps.size > MAX_RECENT) {
            _recentApps.removeRange(MAX_RECENT, _recentApps.size)
        }
        save()
    }

    /** Remove apps that are no longer available on the phone */
    fun pruneUnavailable(availablePackages: Set<String>) {
        val removed = _recentApps.removeAll { it !in availablePackages }
        if (removed) save()
    }

    private fun save() {
        prefs.edit().putString(KEY_RECENT, _recentApps.joinToString(",")).apply()
    }

    companion object {
        const val MAX_RECENT = 5
        private const val KEY_RECENT = "recent_packages"
    }
}
