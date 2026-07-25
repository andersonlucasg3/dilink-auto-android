package com.dilinkauto.client.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.auto.AaDaemonClient
import com.dilinkauto.client.auto.AaUiState
import com.dilinkauto.client.launcher.nav.RecentAppsState
import com.dilinkauto.client.launcher.screen.AppGrid
import com.dilinkauto.client.launcher.screen.NotificationContent
import com.dilinkauto.client.launcher.theme.CarTheme
import com.dilinkauto.client.service.NotificationService

/**
 * Home screen of the AA virtual display. The daemon starts it by explicit
 * component on the VD — the stock launcher is singleTask on display 0, so a
 * generic HOME intent would background the host app.
 *
 * Car-style UI: persistent left nav bar (clock, notifications, recent apps,
 * Home, Back) + app grid with search, plus a notifications panel. All data is
 * local (same process): apps from PackageManager, notifications from
 * [NotificationService.notificationsFlow], nav actions via [AaDaemonClient].
 *
 * Only ever runs on the virtual display: no top bar, no navigation chrome.
 */
class DiLinkLauncher : ComponentActivity() {

    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CarTheme {
                LauncherHome(
                    screen = screen,
                    onScreenChange = { screen = it },
                    onLaunchApp = ::launchApp
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screen = Screen.HOME
    }

    /**
     * Launch inside the car UI viewport via the daemon (shell): freeform
     * window snapped to the content area (right of the 76dp nav bar).
     * App-side ActivityOptions would trip MIUI's wakepath confirmation —
     * the daemon path (shell am) is trusted and doesn't prompt.
     */
    private fun launchApp(packageName: String) {
        try { AaDaemonClient.daemon?.launchApp(packageName) } catch (_: Exception) {}
    }
}

private enum class Screen { HOME, NOTIFICATIONS }

@Composable
private fun LauncherHome(
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val context = LocalContext.current
    val recentAppsState = remember { RecentAppsState(context.applicationContext) }

    val apps by produceState(initialValue = emptyList<LauncherApp>()) {
        value = loadLaunchableApps(context)
    }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            recentAppsState.pruneUnavailable(apps.map { it.packageName }.toSet())
        }
    }

    val notifications by NotificationService.notificationsFlow.collectAsState()

    val launchAndTrack: (String) -> Unit = { pkg ->
        recentAppsState.onAppLaunched(pkg)
        onLaunchApp(pkg)
    }

    // The rail is transient (swipe-in handle) — the launcher uses full width.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                Screen.HOME -> AppGrid(
                    apps = apps,
                    onAppClick = launchAndTrack,
                    modifier = Modifier.fillMaxSize()
                )
                Screen.NOTIFICATIONS -> NotificationContent(
                    notifications = notifications,
                    onAppLaunch = launchAndTrack,
                    onDismiss = { n -> NotificationService.clearNotification(n.packageName, n.id) },
                    onClearAll = { NotificationService.clearAllNotifications() }
                )
            }
        }
    }
}
