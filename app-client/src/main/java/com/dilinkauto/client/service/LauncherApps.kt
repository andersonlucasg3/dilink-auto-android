package com.dilinkauto.client.service

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Single source for the "installed launcher apps" query (audit R3-DRY-08).
 *
 * Three call sites had their own `MAIN`+`LAUNCHER` traversal with different
 * post-processing — this object owns the traversal; each caller picks the
 * variant that matches what it needs:
 *  - [AppListBuilder] → [queryEnabled] (must not ship disabled components)
 *  - [com.dilinkauto.client.AllowlistScreen] → [queryResolveInfos] (shows everything)
 *  - [AllowlistSeeder] → [queryInstalledPackageNames]
 */
internal object LauncherApps {

    /** Raw MAIN+LAUNCHER resolve list. */
    fun queryResolveInfos(pm: PackageManager): List<ResolveInfo> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )

    /**
     * Launcher apps excluding components the user/ROM disabled — Xiaomi HyperOS
     * and some custom ROMs keep the package installed but disable the launcher
     * component; those must not reach the car grid.
     */
    fun queryEnabled(pm: PackageManager): List<ResolveInfo> =
        queryResolveInfos(pm).filter { info ->
            val cn = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

    /** Distinct package names of the launcher apps. */
    fun queryInstalledPackageNames(pm: PackageManager): Set<String> =
        queryResolveInfos(pm).map { it.activityInfo.packageName }.toSet()
}
