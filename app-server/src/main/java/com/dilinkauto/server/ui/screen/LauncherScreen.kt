package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.*

/**
 * Car-optimized home screen — similar to Android Auto / CarPlay.
 *
 * Layout (landscape, 1920x1280 target):
 * ┌──────────────────────────────────────────────┐
 * │ Status Bar (time, phone name, connection)     │
 * ├──────────┬───────────────────────────────────┤
 * │          │                                    │
 * │  Side    │     App Grid / Mirror View         │
 * │  Nav     │                                    │
 * │  Bar     │                                    │
 * │          │                                    │
 * ├──────────┴───────────────────────────────────┤
 * │ Now Playing Bar (if media active)             │
 * └──────────────────────────────────────────────┘
 */
@Composable
fun LauncherScreen(
    service: CarConnectionService,
    onAppClick: (String) -> Unit,
    onNavigateToMirror: () -> Unit
) {
    val state by service.state.collectAsState()
    val phoneName by service.phoneName.collectAsState()
    val appList by service.appList.collectAsState()
    val notifications by service.notifications.collectAsState()
    val mediaMetadata by service.mediaMetadata.collectAsState()
    val playbackState by service.playbackState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Status bar
        CarStatusBar(
            connectionState = state,
            phoneName = phoneName,
            notificationCount = notifications.size
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Side navigation
            SideNavBar(
                onHome = { /* Already on home */ },
                onBack = { service.goBack() },
                onMirror = onNavigateToMirror
            )

            // Main content area
            if (state == CarConnectionService.State.STREAMING && appList.isNotEmpty()) {
                AppGrid(
                    apps = appList,
                    onAppClick = onAppClick,
                    modifier = Modifier.weight(1f)
                )
            } else {
                ConnectionStatus(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onManualConnect = { ip -> service.connectManual(ip) }
                )
            }
        }

        // Now playing bar
        mediaMetadata?.let { metadata ->
            NowPlayingBar(
                metadata = metadata,
                playbackState = playbackState,
                onPlayPause = {
                    val action = if (playbackState?.state == PlaybackState.PLAYING)
                        MediaAction.PAUSE else MediaAction.PLAY
                    service.sendMediaAction(action)
                },
                onNext = { service.sendMediaAction(MediaAction.NEXT) },
                onPrevious = { service.sendMediaAction(MediaAction.PREVIOUS) }
            )
        }
    }
}

@Composable
fun CarStatusBar(
    connectionState: CarConnectionService.State,
    phoneName: String,
    notificationCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0E14))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    when (connectionState) {
                        CarConnectionService.State.STREAMING -> Color(0xFF4CAF50)
                        CarConnectionService.State.CONNECTED -> Color(0xFFFFA726)
                        else -> Color(0xFF757575)
                    },
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (phoneName.isNotEmpty()) phoneName else "Searching…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(Modifier.weight(1f))

        // Notification badge
        if (notificationCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text("$notificationCount")
            }
            Spacer(Modifier.width(16.dp))
        }

        // Clock placeholder — DiLink has its own clock, but we show status
        Text(
            text = "DiLink Auto",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun SideNavBar(
    onHome: () -> Unit,
    onBack: () -> Unit,
    onMirror: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E14))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NavButton(Icons.Default.Home, "Home", onHome)
            Spacer(Modifier.height(8.dp))
            NavButton(Icons.Default.Phonelink, "Mirror", onMirror)
        }
        NavButton(Icons.Default.ArrowBack, "Back", onBack)
    }
}

@Composable
fun NavButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color(0xFFBBBBBB),
            modifier = Modifier.size(28.dp)
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )
    }
}

@Composable
fun AppGrid(
    apps: List<AppInfo>,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        val sorted = apps.sortedBy { it.appName.lowercase() }
        if (searchQuery.isBlank()) sorted
        else sorted.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(filteredApps) { app ->
                AppTile(app = app, onClick = { onAppClick(app.packageName) })
            }
        }

        // Search bar at the bottom
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search apps…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color(0xFF2A2F3A),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Text(
            text = stringResource(R.string.landscape_app_note),
            fontSize = 11.sp,
            color = Color(0xFF666666),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AppTile(app: AppInfo, onClick: () -> Unit) {
    val categoryIcon = when (app.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        AppCategory.OTHER -> Icons.Default.Apps
    }

    val categoryColor = when (app.category) {
        AppCategory.NAVIGATION -> NavigationColor
        AppCategory.MUSIC -> MusicColor
        AppCategory.COMMUNICATION -> CommunicationColor
        AppCategory.OTHER -> OtherColor
    }

    val iconBitmap = remember(app.packageName, app.iconPng.size) {
        if (app.iconPng.isNotEmpty()) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(app.iconPng, 0, app.iconPng.size)
                bmp?.asImageBitmap()
            } catch (_: Exception) { null }
        } else null
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.appName,
                    modifier = Modifier.size(64.dp)
                )
            } else {
                Icon(
                    categoryIcon,
                    contentDescription = null,
                    tint = categoryColor,
                    modifier = Modifier.size(64.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConnectionStatus(
    state: CarConnectionService.State,
    modifier: Modifier,
    onManualConnect: (String) -> Unit = {}
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 500.dp)
        ) {
            when (state) {
                CarConnectionService.State.IDLE -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Searching for phone…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Make sure DiLink Auto is running on your phone\nand both devices are on the same network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )

                    // Manual connect option
                    Spacer(Modifier.height(32.dp))
                    ManualConnectBox(onConnect = onManualConnect)
                }
                CarConnectionService.State.CONNECTING -> {
                    CircularProgressIndicator(
                        color = Color(0xFFFFA726),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Connecting…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.CONNECTED -> {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Connected — waiting for data…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.STREAMING -> {
                    // Won't reach here since we show AppGrid when streaming
                }
            }
        }
    }
}

@Composable
fun ManualConnectBox(onConnect: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val gatewayIp = remember {
        try {
            val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val gw = wm.dhcpInfo.gateway
            if (gw != 0) String.format("%d.%d.%d.%d", gw and 0xFF, (gw shr 8) and 0xFF, (gw shr 16) and 0xFF, (gw shr 24) and 0xFF)
            else ""
        } catch (_: Exception) { "" }
    }
    var ipAddress by remember { mutableStateOf(gatewayIp.ifEmpty { "192.168.43.1" }) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Or connect manually by IP",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("Phone IP address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { if (ipAddress.isNotBlank()) onConnect(ipAddress.trim()) },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
fun NowPlayingBar(
    metadata: MediaMetadata,
    playbackState: PlaybackState?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0E14))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                metadata.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${metadata.artist} — ${metadata.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Controls — large touch targets for car use
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (playbackState?.state == PlaybackState.PLAYING)
                        Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
