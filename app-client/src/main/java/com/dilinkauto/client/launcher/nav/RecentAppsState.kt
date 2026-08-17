package com.dilinkauto.client.launcher.nav

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf

/**
 * Tracks recently launched apps for the nav bar / nav rail.
 *
 * Singleton — [DiLinkLauncher] and [PersistentNavBar] share the same
 * in-memory list. Persisted to SharedPreferences so recents survive process
 * restarts.
 */
object RecentAppsState {

    private var prefs: SharedPreferences? = null

    private val _recentApps = mutableStateListOf<String>()
    val recentApps: List<String> get() = _recentApps

    /** Idempotent — call once from any early entry point (launcher / rail service). */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
        val saved = prefs!!.getString(KEY_RECENT, null)
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

    /** Remove apps that are no longer installed on the phone */
    fun pruneUnavailable(availablePackages: Set<String>) {
        val removed = _recentApps.removeAll { it !in availablePackages }
        if (removed) save()
    }

    private fun save() {
        prefs?.edit()?.putString(KEY_RECENT, _recentApps.joinToString(","))?.apply()
    }

    const val MAX_RECENT = 5
    private const val KEY_RECENT = "recent_packages"
}
