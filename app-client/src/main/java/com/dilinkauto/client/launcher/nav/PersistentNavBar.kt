package com.dilinkauto.client.launcher.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.R
import com.dilinkauto.client.launcher.LauncherApp

/**
 * Persistent left-side navigation bar — always visible on all screens.
 *
 * Layout (top to bottom):
 * - Clock (HH:mm)
 * - Notifications button with badge
 * - Divider
 * - Recent app icons (up to 5)
 * - Spacer (fills remaining space)
 * - Divider
 * - Home button
 * - Back button
 */
@Composable
fun PersistentNavBar(
    recentAppsState: RecentAppsState,
    appList: List<LauncherApp>,
    notificationCount: Int = 0,
    onAppClick: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNotifications: () -> Unit = {}
) {
    val appMap = remember(appList) { appList.associateBy { it.packageName } }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E14))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        // Clock
        ClockDisplay()

        Spacer(Modifier.height(8.dp))

        // Notifications button with badge
        Box {
            NavActionButton(
                icon = Icons.Default.Notifications,
                label = stringResource(R.string.nav_alerts),
                onClick = onNotifications
            )
            if (notificationCount > 0) {
                Badge(
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
            label = stringResource(R.string.nav_home),
            onClick = onHome
        )

        Spacer(Modifier.height(4.dp))

        // Back button
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = stringResource(R.string.nav_back),
            onClick = onBack
        )
    }
}
