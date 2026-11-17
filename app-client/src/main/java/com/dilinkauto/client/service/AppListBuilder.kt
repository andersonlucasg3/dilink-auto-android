package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.AppListMessage
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.DataMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Builds and sends the car-visible app list on behalf of [ConnectionService].
 *
 * The list is filtered by the user's allowlist (see [com.dilinkauto.client.AllowlistScreen])
 * before going on the wire, so the car only receives selected packages — shrinking
 * the wire payload and the car's icon-decode work. Icon data is sent once per
 * package per session ([IconHashGate]); the car's AppIconCache persists them
 * across sessions.
 *
 * List assembly lives in [AppInfoProvider] and the icon suppression in
 * [IconHashGate] (audit R3-SRP-18); this class keeps the allowlist filter and
 * the send.
 */
internal class AppListBuilder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val appInfoProvider = AppInfoProvider(context)
    private val iconGate = IconHashGate()

    /** Re-send the app list to the car. No-op if [conn] is null. */
    fun sendAppList(conn: Connection?) {
        if (conn == null) return

        scope.launch(Dispatchers.IO) {
            try {
                val allApps = appInfoProvider.build(iconGate)
                val apps = filterByAllowlist(allApps)

                conn.sendData(DataMsg.APP_LIST, AppListMessage(apps).encode())
                val skipped = apps.count { it.iconPng.isEmpty() }
                FileLog.i(TAG, "App list sent: ${apps.size}/${allApps.size} apps allowed (${skipped} icons skipped/unchanged)")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send app list", e)
            }
        }
    }

    /**
     * Apply the user's car-app allowlist: only selected packages reach the car,
     * shrinking the wire payload and the car's icon-decode work. On first run
     * the allowlist is pre-seeded with common map apps that are actually installed.
     * A null selection (never configured) passes everything through.
     */
    private fun filterByAllowlist(allApps: List<AppInfo>): List<AppInfo> {
        val allowed = allowedPackages()
        return if (allowed != null) allApps.filter { it.packageName in allowed } else allApps
    }

    private val allowlistRepo = AllowlistRepository(context)

    /**
     * 当前「允许上车」的包名集合；`null` = 从未配置（全量放行）。
     *
     * issue #21：引擎（shell UID 的 vd-server）读不到本 App 的 prefs，所以部署引擎时
     * 由 [com.dilinkauto.client.service.ConnectionService] 取这份集合、经 argv 下传，
     * 让建屏恢复（`ensureContent`）只在车机 App 列表内挑选目标。与 [filterByAllowlist]
     * 共用同一份读取/首启播种逻辑，保证「引擎看到的集合」= 「发给车机的集合」。
     */
    internal fun allowedPackages(): Set<String>? {
        return allowlistRepo.getAllowedPackages(context.packageManager, seedIfUnconfigured = true)
    }

    /** Clear the icon-hash cache so the next [sendAppList] re-sends every icon. */
    fun resetIconHashes() {
        iconGate.reset()
    }

    companion object {
        private const val TAG = "AppListBuilder"
    }
}
