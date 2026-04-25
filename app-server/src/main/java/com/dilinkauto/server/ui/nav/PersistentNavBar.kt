package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.AppInfo

/**
 * Persistent left-side navigation bar — always visible on all screens.
 *
 * Layout (top to bottom):
 * - Clock (HH:mm)
 * - Network status
 * - Divider
 * - Recent app icons (3-5)
 * - Spacer (fills remaining space)
 * - Divider
 * - Back button
 * - Home button
 */
@Composable
fun PersistentNavBar(
    recentAppsState: RecentAppsState,
    activeAppPackage: String?,
    isPhoneConnected: Boolean,
    appList: List<AppInfo>,
    notificationCount: Int = 0,
    onAppClick: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNotifications: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    val appMap = remember(appList) { appList.associateBy { it.packageName } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenWidthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let {
        (it * density.density).toInt()
    }
    val navBarPx = com.dilinkauto.server.service.CarConnectionService.navBarWidthPx(density.density, screenWidthPx)
    val navBarDp = with(density) { navBarPx.toDp() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(navBarDp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E14))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        // Clock
        ClockDisplay()

        Spacer(Modifier.height(8.dp))

        // Disconnect / Connection status
        if (isPhoneConnected) {
            NavActionButton(
                icon = Icons.Default.LinkOff,
                label = "Eject",
                onClick = onDisconnect,
                tint = Color(0xFFFF5252)
            )
        } else {
            NetworkInfo(isConnected = false)
        }

        Spacer(Modifier.height(8.dp))

        // Notifications button with badge
        Box {
            NavActionButton(
                icon = Icons.Default.Notifications,
                label = "Alerts",
                onClick = onNotifications
            )
            if (notificationCount > 0) {
                androidx.compose.material3.Badge(
                    containerColor = Color(0xFFFF5252),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp)
                ) {
                    Text("$notificationCount")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Divider
        Divider(color = Color(0xFF2A2F3A), thickness = 1.dp, modifier = Modifier.padding(horizontal = 8.dp))

        Spacer(Modifier.height(8.dp))

        // Recent apps
        recentAppsState.recentApps.forEach { pkg ->
            RecentAppIcon(
                app = appMap[pkg],
                isActive = pkg == activeAppPackage,
                onClick = { onAppClick(pkg) }
            )
            Spacer(Modifier.height(4.dp))
        }

        // Push bottom buttons to the bottom
        Spacer(Modifier.weight(1f))

        // Divider
        Divider(color = Color(0xFF2A2F3A), thickness = 1.dp, modifier = Modifier.padding(horizontal = 8.dp))

        Spacer(Modifier.height(8.dp))

        // Home button
        NavActionButton(
            icon = Icons.Default.Home,
            label = "Home",
            onClick = onHome
        )

        Spacer(Modifier.height(4.dp))

        // Back button
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = "Back",
            onClick = onBack
        )
    }
}
