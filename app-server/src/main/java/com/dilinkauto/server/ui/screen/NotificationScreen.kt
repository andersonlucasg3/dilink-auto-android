package com.dilinkauto.server.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.NotificationData
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.service.CarConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Notification list content — header with "Clear All", scrollable cards
 * showing real app icons, relative timestamps, and per-item dismiss.
 */
@Composable
fun NotificationContent(service: CarConnectionService, onAppLaunch: (String) -> Unit = {}) {
    val notifications by service.notifications.collectAsState()
    val sorted = remember(notifications) { notifications.sortedByDescending { it.timestamp } }

    if (notifications.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.no_notifications),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                NotificationHeader(
                    count = sorted.size,
                    onClearAll = { service.clearAllNotifications() }
                )
            }
            items(sorted, key = { "n-${it.packageName}-${it.id}" }) { notification ->
                NotificationCard(
                    notification = notification,
                    onTap = { onAppLaunch(notification.packageName) },
                    onDismiss = { service.clearNotification(notification.id, notification.packageName) }
                )
            }
        }
    }
}

@Composable
private fun NotificationHeader(count: Int, onClearAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.notifications_count, count),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White
        )
        TextButton(onClick = onClearAll) {
            Text(
                stringResource(R.string.clear_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun NotificationCard(
    notification: NotificationData,
    onTap: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 4 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 4 })
    ) {
        Card(
            modifier = modifier.fillMaxWidth().clickable { onTap() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // App icon or fallback
                AppIcon(iconPng = notification.iconPng, packageName = notification.packageName, modifier = Modifier.size(40.dp))

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            notification.appName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatRelativeTime(notification.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    if (notification.title.isNotEmpty()) {
                        Text(
                            notification.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (notification.text.isNotEmpty()) {
                        Text(
                            notification.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFBBBBBB),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (notification.progressIndeterminate) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (notification.progressMax > 0) {
                        Spacer(Modifier.height(6.dp))
                        @Suppress("DEPRECATION")
                        LinearProgressIndicator(
                            progress = notification.progress.toFloat() / notification.progressMax,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Dismiss button
                IconButton(
                    onClick = {
                        visible = false
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_notification),
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(iconPng: ByteArray, packageName: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val iconSizePx = with(density) { 40.dp.toPx().toInt() }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(packageName, iconSizePx) {
        withContext(Dispatchers.IO) {
            // Try car cache first (has high-res source from app list)
            val cached = ServerApp.iconCache.get(packageName, iconSizePx)
            if (cached != null) {
                bitmap = cached
                return@withContext
            }
            // Fall back to notification's own icon data
            if (iconPng.isNotEmpty()) {
                try {
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(iconPng, 0, iconPng.size)
                } catch (_: Exception) {}
            }
        }
    }

    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap!!.asImageBitmap()),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        else -> {
            val sdf = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}
