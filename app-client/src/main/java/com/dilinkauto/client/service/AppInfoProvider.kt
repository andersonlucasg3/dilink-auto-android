package com.dilinkauto.client.service

import android.content.Context
import android.content.pm.PackageManager
import com.dilinkauto.client.ClientApp
import com.dilinkauto.protocol.AppInfo

/**
 * Assembles the wire-ready [AppInfo] list (audit R3-SRP-18): query the enabled
 * launcher apps, derive each package's change hash, gate the icon through
 * [IconHashGate], categorize and sort.
 *
 * Extracted from `AppListBuilder`, which keeps only the allowlist filter and
 * the actual send.
 */
internal class AppInfoProvider(private val context: Context) {

    fun build(iconGate: IconHashGate): List<AppInfo> {
        val pm = context.packageManager
        // Hidden-component filtering lives in LauncherApps (audit R3-DRY-08).
        return LauncherApps.queryEnabled(pm).map { info ->
            val pkg = info.activityInfo.packageName
            // Use lastUpdateTime as a lightweight change indicator. The car-side
            // AppIconCache handles persistence, multi-size resizing, and
            // in-memory Bitmap caching.
            val hash = packageHash(pm, pkg)
            AppInfo(
                pkg,
                info.loadLabel(pm).toString(),
                AppCategorizer.categorize(pkg),
                iconGate.iconFor(pkg, hash) { ClientApp.loadIconPng(pm, pkg, 192, cacheKey = hash) },
                hash
            )
        }.sortedBy { it.category.id }
    }

    private fun packageHash(pm: PackageManager, pkg: String): String = try {
        pm.getPackageInfo(pkg, 0).lastUpdateTime.toString()
    } catch (_: Exception) {
        ""
    }
}
