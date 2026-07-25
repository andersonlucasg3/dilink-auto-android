package com.dilinkauto.client.launcher.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.R
import com.dilinkauto.client.launcher.LauncherApp
import com.dilinkauto.client.launcher.LauncherIconCache
import com.dilinkauto.client.launcher.launchAppInfo
import com.dilinkauto.client.launcher.launchUninstall
import com.dilinkauto.client.launcher.theme.*
import com.dilinkauto.protocol.AppCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

/** Loads (or fetches from cache) the launcher icon for [packageName] on IO. */
@Composable
fun rememberAppIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    var iconBitmap by remember(packageName) {
        mutableStateOf(packageName?.let { LauncherIconCache.get(it) })
    }
    LaunchedEffect(packageName) {
        val pkg = packageName ?: return@LaunchedEffect
        if (iconBitmap == null) {
            iconBitmap = withContext(Dispatchers.IO) { LauncherIconCache.load(context, pkg) }
        }
    }
    return iconBitmap
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentAppIcon(
    app: LauncherApp?,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    val categoryIcon = when (app?.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        else -> Icons.Default.Apps
    }

    val iconBitmap = rememberAppIcon(app?.packageName)

    // Context menu state
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (app != null) menuExpanded = true }
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp)
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = app?.label,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        categoryIcon,
                        contentDescription = app?.label,
                        tint = Color(0xFFBBBBBB),
                        modifier = Modifier.size(40.dp)
                    )
                }
                Text(
                    text = app?.label ?: stringResource(R.string.recent_app_fallback),
                    fontSize = 14.sp,
                    color = Color(0xFF888888),
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
                        launchUninstall(context, app.packageName)
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
                        launchAppInfo(context, app.packageName)
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
