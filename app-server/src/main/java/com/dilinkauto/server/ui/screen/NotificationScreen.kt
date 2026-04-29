package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.NotificationData
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification list content — no header or back button (nav bar handles that).
 */
@Composable
fun NotificationContent(service: CarConnectionService, onAppLaunch: (String) -> Unit = {}) {
    val notifications by service.notifications.collectAsState()

    if (notifications.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notifications.sortedByDescending { it.timestamp }) { notification ->
                NotificationCard(notification, onTap = { onAppLaunch(notification.packageName) })
            }
        }
    }
}

@Composable
fun NotificationCard(notification: NotificationData, onTap: () -> Unit = {}) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        notification.appName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        timeFormat.format(Date(notification.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                if (notification.title.isNotEmpty()) {
                    Text(
                        notification.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notification.text.isNotEmpty()) {
                    Text(
                        notification.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBBBBBB),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notification.progressIndeterminate) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (notification.progressMax > 0) {
                    Spacer(Modifier.height(8.dp))
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = notification.progress.toFloat() / notification.progressMax,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
