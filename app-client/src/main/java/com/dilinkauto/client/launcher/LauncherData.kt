package com.dilinkauto.client.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.dilinkauto.protocol.AppCategory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local model for a launchable app installed on the phone. */
data class LauncherApp(
    val packageName: String,
    val label: String,
    val category: AppCategory
)

/** Simple in-memory icon cache keyed by package name. */
object LauncherIconCache {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun get(packageName: String): ImageBitmap? = cache[packageName]

    /** Blocking — call from an IO dispatcher. */
    fun load(context: Context, packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        return try {
            val appContext = context.applicationContext
            val bitmap = appContext.packageManager
                .getApplicationIcon(packageName)
                .toBitmap()
                .asImageBitmap()
            cache[packageName] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}

/** Loads all launchable apps, excluding our own package. Blocking IO. */
suspend fun loadLaunchableApps(context: Context): List<LauncherApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            LauncherApp(
                packageName = it.activityInfo.packageName,
                label = it.loadLabel(pm).toString(),
                category = mapCategory(it.activityInfo.applicationInfo)
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun mapCategory(info: ApplicationInfo): AppCategory = when (info.category) {
    ApplicationInfo.CATEGORY_MAPS -> AppCategory.NAVIGATION
    ApplicationInfo.CATEGORY_AUDIO -> AppCategory.MUSIC
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.COMMUNICATION
    else -> AppCategory.OTHER
}

/** Opens the system uninstall dialog for [packageName]. */
fun launchUninstall(context: Context, packageName: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    } catch (_: Exception) {}
}

/** Opens the system app-info screen for [packageName]. */
fun launchAppInfo(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    } catch (_: Exception) {}
}
