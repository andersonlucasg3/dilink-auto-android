package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.server.ui.theme.*
import kotlinx.coroutines.delay
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
            text = if (isConnected) "Connected" else "Offline",
            fontSize = 11.sp,
            color = Color(0xFF888888),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun RecentAppIcon(
    app: AppInfo?,
    isActive: Boolean,
    onClick: () -> Unit
) {
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

    val iconBitmap = remember(app?.packageName, app?.iconPng?.size) {
        val png = app?.iconPng
        if (png != null && png.isNotEmpty()) {
            try {
                android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        } else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFF1E2430) else Color.Transparent)
            .clickable(onClick = onClick)
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
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
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
                text = app?.appName ?: "App",
                fontSize = 14.sp,
                color = if (isActive) Color.White else Color(0xFF888888),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
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
