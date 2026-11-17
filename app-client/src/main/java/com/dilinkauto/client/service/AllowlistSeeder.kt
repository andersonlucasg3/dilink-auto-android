package com.dilinkauto.client.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.dilinkauto.client.FileLog

/**
 * First-run seeding of the car's app allowlist.
 *
 * Extracted from [AppListBuilder] (docs/audit-srp-dry.md SRP-7): querying the
 * launcher and writing a preference has nothing to do with building the app list
 * or suppressing unchanged icons.
 *
 * Runs once per install. Afterwards the user curates the list from the UI, so
 * re-seeding would overwrite their choices.
 */
internal class AllowlistSeeder(
    private val packagesKey: String = AllowlistRepository.KEY_PACKAGES,
    private val configuredKey: String = AllowlistRepository.KEY_CONFIGURED
) {

    /**
     * Intersects [DEFAULT_MAP_PACKAGES] with the launcher apps actually
     * installed and persists the result, marking the allowlist configured.
     *
     * Defaults that are not installed are simply no-ops, so a user with none of
     * them gets an empty (but configured) allowlist rather than an unconfigured
     * one that would re-seed on every launch.
     */
    fun seedIfNeeded(pm: PackageManager, prefs: SharedPreferences) {
        if (prefs.getBoolean(configuredKey, false)) return

        val installedLauncher = LauncherApps.queryInstalledPackageNames(pm)

        val seed = DEFAULT_MAP_PACKAGES.intersect(installedLauncher)
        prefs.edit()
            .putStringSet(packagesKey, seed)
            .putBoolean(configuredKey, true)
            .apply()
        FileLog.i(TAG, "Allowlist seeded with ${seed.size} default map apps: $seed")
    }

    companion object {
        private const val TAG = "AllowlistSeeder"

        /** Common map apps pre-seeded on first run. */
        val DEFAULT_MAP_PACKAGES = setOf(
            "com.baidu.BaiduMap",            // Baidu Maps
            "com.autonavi.minimap",          // AMap (Gaode)
            "com.google.android.apps.maps", // Google Maps
            "com.waze",                      // Waze
            "com.soso.map",                  // Sogou Map
            "com.tencent.map",               // Tencent Map
            "com.mapabc.mapabc"              // Mapabc
        )
    }
}