package com.dilinkauto.client.service

import android.content.Context
import android.content.pm.PackageManager

/**
 * Read an installed app's version label as a string, with a versionCode
 * fallback for pre-semver peers.
 *
 * Extracted from [ConnectionService] (which built this string at multiple
 * sites: car-install version check and app-info data) and from
 * [com.dilinkauto.client.ui.SettingsScreen]'s about card. The
 * `versionName ?: versionCode.toString()` shape with the deprecation
 * suppression was duplicated at each site.
 *
 * @param preferCode when true, return versionCode as a string (used when the
 *                   peer only speaks versionCode integers — pre-0.17.0 cars).
 */
object AppVersion {
    /**
     * The app's versionName, falling back to versionCode when name is null.
     *
     * Defaults to the calling app's own package; pass [packageName] to read
     * another app (e.g. when the car reports its package in a handshake).
     */
    fun label(context: Context, preferCode: Boolean = false, packageName: String = context.packageName): String {
        val pi = context.packageManager.getPackageInfo(packageName, 0)
        return if (preferCode) {
            @Suppress("DEPRECATION")
            pi.versionCode.toString()
        } else {
            pi.versionName ?: @Suppress("DEPRECATION") pi.versionCode.toString()
        }
    }

    /** versionName or empty string — used by AppInfoDataMessage and the about card. */
    fun nameOrEmpty(context: Context, packageName: String = context.packageName): String =
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
}

