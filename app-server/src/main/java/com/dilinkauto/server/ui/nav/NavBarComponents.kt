package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClockDisplay() {
    var time by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            time = timeFormat.format(Date())
            delay(1000)
        }
    }

    Text(
        text = time,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun NetworkInfo(isConnected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = "Network",
            tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFF757575),
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = if (isConnected) stringResource(R.string.network_connected) else stringResource(R.string.network_offline),
            fontSize = 11.sp,
            color = Color(0xFF888888),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentAppIcon(
    app: AppInfo?,
    isActive: Boolean,
    service: CarConnectionService,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val iconSizePx = with(density) { 40.dp.toPx().toInt() }

    val categoryIcon = when (app?.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        else -> Icons.Default.Apps
    }

    val categoryColor = when (app?.category) {
        AppCategory.NAVIGATION -> NavigationColor
        AppCategory.MUSIC -> MusicColor
        AppCategory.COMMUNICATION -> CommunicationColor
        else -> OtherColor
    }

    var iconBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(app?.packageName, iconSizePx) {
        val pkg = app?.packageName ?: return@LaunchedEffect
        try {
            val bmp = withContext(Dispatchers.IO) {
                ServerApp.iconCache.get(pkg, iconSizePx)
            }
            if (bmp != null) {
                iconBitmap = bmp.asImageBitmap()
            }
        } catch (_: Exception) {}
    }

    // Context menu state
    var menuExpanded by remember { mutableStateOf(false) }
    val shortcutsCache by service.shortcutsCache.collectAsState()
    val shortcuts = app?.packageName?.let { shortcutsCache[it] }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isActive) Color(0xFF1E2430) else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (app != null) {
                            menuExpanded = true
                            // TODO: Re-enable when app shortcuts are revisited.
                            // See LauncherScreen.AppTile and issue #57.
                            // service.requestShortcuts(app.packageName)
                        }
                    }
                )
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp)
            ) {
                val bitmap = iconBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = app?.appName,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        categoryIcon,
                        contentDescription = app?.appName,
                        tint = if (isActive) categoryColor else Color(0xFFBBBBBB),
                        modifier = Modifier.size(40.dp)
                    )
                }
                Text(
                    text = app?.appName ?: stringResource(R.string.recent_app_fallback),
                    fontSize = 14.sp,
                    color = if (isActive) Color.White else Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Dropdown context menu
        if (app != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                offset = DpOffset(80.dp, 0.dp),
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_uninstall),
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        service.requestUninstall(app.packageName)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete, null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_app_info),
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        service.requestAppInfo(app.packageName)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Info, null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                // ── App shortcuts ──────────────────────────────────────────
                // TODO: Revisit in a future release. Infrastructure is in
                // place (VD server query + APK XML fallback, shell execution)
                // but disabled while label resolution and reliability are
                // refined. To re-enable, flip the constant and sync with
                // LauncherScreen.AppTile.
                // See: https://github.com/andersonlucasg3/dilink-auto-android/issues/57
                val shortcutsEnabled = false
                if (shortcutsEnabled && shortcuts != null && shortcuts.isNotEmpty()) {
                    Divider(
                        color = Color(0xFF2A2F3A),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    shortcuts.forEach { shortcut ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    shortcut.shortLabel.ifEmpty { shortcut.longLabel },
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                service.executeShortcut(app.packageName, shortcut.id)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.OpenInNew, null,
                                    tint = Color(0xFF81C784),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NavActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color(0xFFBBBBBB)
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF888888)
        )
    }
}
