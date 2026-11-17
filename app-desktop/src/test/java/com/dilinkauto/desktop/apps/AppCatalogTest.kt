package com.dilinkauto.desktop.apps

import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.AppListMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppCatalog] 单测：全量列表替换、图标沿用、卸载移除。 */
class AppCatalogTest {

    private fun app(
        pkg: String,
        name: String = pkg.substringAfterLast('.'),
        category: AppCategory = AppCategory.OTHER,
        iconPng: ByteArray = ByteArray(0),
    ) = AppInfo(packageName = pkg, appName = name, category = category, iconPng = iconPng)

    @Test
    fun onAppList_replacesWholeListAndDecodesIcons() {
        val catalog = AppCatalog()
        catalog.onAppList(
            AppListMessage(
                listOf(
                    app("com.a", "应用A", AppCategory.NAVIGATION, TestIcons.pngBytes()),
                    app("com.b", "应用B", AppCategory.MUSIC),
                )
            )
        )

        val apps = catalog.apps.value
        assertEquals(listOf("com.a", "com.b"), apps.map { it.packageName })
        assertEquals("应用A", apps[0].appName)
        assertEquals(AppCategory.NAVIGATION, apps[0].category)
        assertNotNull(apps[0].icon)
        // 没带图标的项退化成 null，UI 用首字母占位
        assertNull(apps[1].icon)
    }

    @Test
    fun onAppList_withBlankName_fallsBackToPackageName() {
        val catalog = AppCatalog()
        catalog.onAppList(AppListMessage(listOf(app("com.a", name = "   "))))
        assertEquals("com.a", catalog.apps.value[0].appName)
    }

    @Test
    fun secondAppList_keepsIconWhenPhoneSuppressesRepeat() {
        // 真机行为：图标 hash 未变时后续 APP_LIST 的 iconPng 为空，
        // 桌面端必须沿用第一次解码出来的位图。
        val catalog = AppCatalog()
        catalog.onAppList(AppListMessage(listOf(app("com.a", "应用A", iconPng = TestIcons.pngBytes()))))
        val firstIcon = catalog.apps.value[0].icon

        catalog.onAppList(AppListMessage(listOf(app("com.a", "应用A"), app("com.b", "应用B"))))

        val apps = catalog.apps.value
        assertEquals(listOf("com.a", "com.b"), apps.map { it.packageName })
        assertSame(firstIcon, apps[0].icon)
    }

    @Test
    fun onUninstalled_dropsAppAndIcon() {
        val catalog = AppCatalog()
        catalog.onAppList(
            AppListMessage(
                listOf(
                    app("com.a", iconPng = TestIcons.pngBytes()),
                    app("com.b", iconPng = TestIcons.pngBytes()),
                )
            )
        )

        catalog.onUninstalled("com.a")

        assertEquals(listOf("com.b"), catalog.apps.value.map { it.packageName })
    }

    @Test
    fun onUninstalled_forUnknownPackage_isNoOp() {
        val catalog = AppCatalog()
        catalog.onAppList(AppListMessage(listOf(app("com.a"))))
        val before = catalog.apps.value

        catalog.onUninstalled("com.unknown")

        assertSame(before, catalog.apps.value)
    }
}
