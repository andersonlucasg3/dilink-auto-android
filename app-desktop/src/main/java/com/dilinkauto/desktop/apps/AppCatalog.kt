package com.dilinkauto.desktop.apps

import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppListMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

/**
 * 应用列表里的一项：协议字段 + 已解码的图标。
 *
 * [icon] 为 null 表示手机还没给过这个包的图标（首次列表刷新时可能如此），
 * UI 会退化成"首字母占位块"。
 */
data class AppEntry(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val icon: BufferedImage? = null,
)

/**
 * 应用目录：把手机推来的 `APP_LIST` / `APP_UNINSTALLED` 收敛成 UI 可直接消费的
 * [StateFlow]。
 *
 * 数据来源与时机（真机行为）：
 *  - `APP_LIST` 走控制口的 `Channel.DATA`，握手成功后推一次，白名单变更时再推；
 *  - 每次推送都是**全量列表**，所以这里整体替换而非增量合并；
 *  - 卸载走 `APP_UNINSTALLED`，载荷是包名字符串（不是 AppListMessage）。
 *
 * 线程模型：`onAppList` / `onUninstalled` 在数据帧分发协程（`Dispatchers.IO`）里被调用，
 * PNG 解码也就在 IO 线程完成——**不要在 UI 线程直接调用这两个方法**。
 */
class AppCatalog(
    private val icons: AppIconStore = AppIconStore(),
) {
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())

    /** 当前应用列表，供 Compose 直接 collect。 */
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    fun onAppList(message: AppListMessage) {
        val entries = message.apps.map { info ->
            AppEntry(
                packageName = info.packageName,
                // 名字缺失时退回包名，避免网格里出现空白标签。
                appName = info.appName.ifBlank { info.packageName },
                category = info.category,
                icon = icons.put(info.packageName, info.iconPng),
            )
        }
        // 手机侧抑制重复图标时 icon 是同一个实例引用，列表内容不变 → 不触发重组。
        if (entries != _apps.value) _apps.value = entries
    }

    fun onUninstalled(packageName: String) {
        icons.remove(packageName)
        val current = _apps.value
        val next = current.filterNot { it.packageName == packageName }
        if (next.size != current.size) _apps.value = next
    }

    // 会话切换不需要"清空"这一动作：DesktopApp.openSession() 每代新建一个 AppCatalog
    // （图标缓存随之丢弃）。此前这里有个 clear() 从未被生产路径调用，已删（audit WIN-10）。
}
