package com.dilinkauto.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.nav.PersistentNavBar
import com.dilinkauto.server.ui.nav.RecentAppsState
import com.dilinkauto.server.ui.screen.CarLaunchScreen
import com.dilinkauto.server.ui.screen.HomeContent
import com.dilinkauto.server.ui.screen.MirrorContent
import com.dilinkauto.server.ui.screen.NotificationContent
import com.dilinkauto.server.ui.theme.CarTheme

class MainActivity : ComponentActivity() {

    private var carService: CarConnectionService? = null
    private var serviceBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            carService = (binder as CarConnectionService.LocalBinder).service
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, CarConnectionService::class.java).apply {
            action = CarConnectionService.ACTION_START
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Handle USB device attached intent (from manifest intent-filter)
        handleUsbIntent(intent)

        setContent {
            CarTheme {
                if (serviceBound) {
                    carService?.let { service ->
                        CarShell(service)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                android.hardware.usb.UsbManager.EXTRA_DEVICE
            )
            if (device != null) {
                android.util.Log.i("MainActivity", "USB device from intent: ${device.productName}")
                // Forward to service — it handles USB ADB
                carService?.onUsbDeviceFromActivity(device)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}

enum class Screen {
    HOME, APP, NOTIFICATIONS
}

@Composable
fun CarShell(service: CarConnectionService) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recentAppsState = remember { RecentAppsState(context) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var activeAppPackage by remember { mutableStateOf<String?>(null) }

    val state by service.state.collectAsState()
    val appList by service.appList.collectAsState()
    val notifications by service.notifications.collectAsState()
    val statusMessage by service.statusMessage.collectAsState()
    val videoReady by service.videoReady.collectAsState()
    val isConnected = state == CarConnectionService.State.STREAMING ||
            state == CarConnectionService.State.CONNECTED

    // When VD stack empties (after back presses), go to home screen
    LaunchedEffect(Unit) {
        service.vdStackEmpty.collect {
            currentScreen = Screen.HOME
            activeAppPackage = null
        }
    }

    // When the focused app changes on the VD (after back presses close an app),
    // update the nav bar to reflect the new active app
    LaunchedEffect(Unit) {
        service.focusedApp.collect { pkg ->
            if (pkg != null) {
                activeAppPackage = pkg
                currentScreen = Screen.APP
            }
        }
    }

    // Prune recent apps when the app list updates
    LaunchedEffect(appList) {
        if (appList.isNotEmpty()) {
            recentAppsState.pruneUnavailable(appList.map { it.packageName }.toSet())
        }
    }

    val launchApp: (String) -> Unit = { pkg ->
        service.launchApp(pkg)
        activeAppPackage = pkg
        recentAppsState.onAppLaunched(pkg)
        currentScreen = Screen.APP
    }

    // App info dialog
    val appInfoData by service.appInfoData.collectAsState()
    if (appInfoData != null) {
        val info = appInfoData!!
        val dateStr = remember(info.installTime) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(info.installTime))
        }
        AlertDialog(
            onDismissRequest = { service.clearAppInfoData() },
            title = { Text(info.appName, color = Color.White, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Package", info.packageName)
                    InfoRow("Version", info.versionName)
                    InfoRow("Version code", info.versionCode.toString())
                    InfoRow("Target SDK", info.targetSdk.toString())
                    InfoRow("Installed", dateStr)
                }
            },
            confirmButton = {
                TextButton(onClick = { service.clearAppInfoData() }) {
                    Text("OK", fontSize = 16.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }

    val showStreamingMode = appList.isNotEmpty() && isConnected

    if (showStreamingMode) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Persistent left nav bar (streaming mode only)
            PersistentNavBar(
                recentAppsState = recentAppsState,
                activeAppPackage = activeAppPackage,
                isPhoneConnected = isConnected,
                appList = appList,
                service = service,
                notificationCount = notifications.size,
                onAppClick = launchApp,
                onBack = {
                    service.goBack()
                },
                onHome = {
                    service.goHome()
                    currentScreen = Screen.HOME
                    activeAppPackage = null
                },
                onNotifications = {
                    currentScreen = if (currentScreen == Screen.NOTIFICATIONS) Screen.HOME
                        else Screen.NOTIFICATIONS
                },
                onDisconnect = {
                    service.disconnectFromPhone()
                    currentScreen = Screen.HOME
                    activeAppPackage = null
                }
            )

            // Content area
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // MirrorContent is always composed — never removed during screen navigation.
                // INVISIBLE when not on Screen.APP keeps the TextureView surface alive.
                // This eliminates decoder restart storms (stop+start = 3 keyframes dropped,
                // ~3s of visual artifacts per navigation event).
                MirrorContent(service = service, visible = currentScreen == Screen.APP)

                // Content overlays — rendered on top of the TextureView
                val showVideoWaitOverlay = !videoReady && currentScreen != Screen.NOTIFICATIONS
                when {
                    showVideoWaitOverlay -> {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                                androidx.compose.material3.Text(
                                    statusMessage.ifEmpty { stringResource(R.string.status_starting_vd) },
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                    currentScreen == Screen.HOME -> HomeContent(service = service, onAppClick = launchApp)
                    currentScreen == Screen.NOTIFICATIONS -> NotificationContent(service = service, onAppLaunch = launchApp)
                }
            }
        }
    } else {
        // Full-screen launch / connection screen — no nav bar, connection-focused
        CarLaunchScreen(service = service)
    }
}

@androidx.compose.runtime.Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "$label:",
            color = Color(0xFF888888),
            fontSize = 14.sp
        )
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}
