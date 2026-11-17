package com.dilinkauto.server.service

import android.content.Context
import android.graphics.BitmapFactory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.AppInfoDataMessage
import com.dilinkauto.protocol.AppListMessage
import com.dilinkauto.server.ServerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 负责接收和解析手机下发的应用元数据、执行单图标/聚合图标内存预算安全门控，
 * 以及异步将图标写入持久化与解码缓存（从 [CarConnectionService] 剥离以遵循 SRP）。
 */
class CarAppListReceiver(
    private val context: Context,
    private val log: (msg: String, level: String) -> Unit = { _, _ -> },
) {
    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _appInfoData = MutableStateFlow<AppInfoDataMessage?>(null)
    val appInfoData: StateFlow<AppInfoDataMessage?> = _appInfoData.asStateFlow()

    fun onAppList(payload: ByteArray, scope: CoroutineScope) {
        val apps = AppListMessage.decode(payload).apps
        // Set the app list immediately so the grid can render placeholder
        // (category-icon) tiles while icons are still decoding. The phone's
        // icon PNGs are persisted on Dispatchers.IO below — never on Main.
        _appList.value = apps
        scope.launch(Dispatchers.IO) {
            var newIcons = 0
            var totalIconBytes = 0L
            apps.forEach { app ->
                if (app.iconPng.isEmpty()) return@forEach
                // Aggregate cap (audit S-M5): the frame limit is 128MB and
                // a 4GB car head unit OOMs decoding the whole batch — this
                // also bounds the eMMC source cache written below.
                if (!iconBudgetAccepts(totalIconBytes, app.iconPng.size)) {
                    log("App list: icon budget exhausted after $newIcons icons — skipping the rest", "W")
                    return@launch
                }
                totalIconBytes += app.iconPng.size
                // Per-icon cap: a PNG decompression bomb (small bytes,
                // huge pixel dimensions) must never reach BitmapFactory.
                // `inJustDecodeBounds` reads only the header, so this is cheap (audit S-M5).
                if (!iconWithinLimits(app.packageName, app.iconPng)) return@forEach
                ServerApp.iconCache.putSource(app.packageName, app.iconPng)
                newIcons++
            }
            // Decode + resize all icons on a background thread BEFORE the grid renders.
            val density = context.resources.displayMetrics.density
            val gridIconPx = (64 * density).toInt()
            val prepared = ServerApp.iconCache.prepareAll(apps, gridIconPx)
            log("App list: ${apps.size} apps, ${prepared} icons prepared @ ${gridIconPx}px", "I")
        }
    }

    fun onAppUninstalled(payload: ByteArray) {
        val pkg = String(payload, Charsets.UTF_8)
        _appList.value = _appList.value.filter { it.packageName != pkg }
        ServerApp.iconCache.evict(pkg)
        log("App uninstalled: $pkg — removed from grid, evicted icon cache", "I")
    }

    fun onAppInfoData(payload: ByteArray) {
        val info = AppInfoDataMessage.decode(payload)
        _appInfoData.value = info
        log("App info received: ${info.packageName} v${info.versionName}", "I")
    }

    fun clearAppInfoData() {
        _appInfoData.value = null
    }

    fun reset() {
        _appList.value = emptyList()
        _appInfoData.value = null
        ServerApp.iconCache.clear()
    }

    /**
     * 单张图标尺寸与安全字节上限校验门控（对齐 MAX_ICON_DIM = 512, MAX_ICON_BYTES = 1048576）。
     */
    fun iconWithinLimits(packageName: String, png: ByteArray): Boolean {
        if (png.size > MAX_ICON_BYTES) {
            log("App icon too large, skipped: $packageName (${png.size}B > $MAX_ICON_BYTES)", "W")
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                // Header unreadable — let normal decode path fail later on its own
                true
            } else if (w > MAX_ICON_DIM || h > MAX_ICON_DIM) {
                log("App icon too big, skipped: $packageName (${w}x${h} > $MAX_ICON_DIM)", "W")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            log("App icon header decode failed, keeping: $packageName (${e.message})", "W")
            true
        }
    }

    companion object {
        /**
         * 单张图标解码前的宽高上限（audit S-M5, D-M5 对齐）。
         */
        const val MAX_ICON_DIM = 512

        /**
         * 单张图标的原始 PNG 字节上限（1MB，audit S-M5, D-M5 对齐）。
         */
        const val MAX_ICON_BYTES = 1048576 // 1 shl 20

        /**
         * 单个 APP_LIST 消息的聚合图标字节预算（64MB，audit S-M5）。
         */
        const val MAX_APP_LIST_ICON_BYTES = 64L shl 20 // 64 MB

        /** 追加 [nextIconBytes] 后是否仍在 [MAX_APP_LIST_ICON_BYTES] 聚合预算内（含边界）。 */
        fun iconBudgetAccepts(totalBytes: Long, nextIconBytes: Int): Boolean =
            totalBytes + nextIconBytes <= MAX_APP_LIST_ICON_BYTES
    }
}
