package com.dilinkauto.client

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.service.ConnectionService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request all-files access (needed to deploy VD server JAR to shared storage)
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }

        // Request battery optimization exemption — prevents HyperOS/MIUI "greeze"
        // (background process freezing) that suspends the video relay when screen is off
        requestBatteryExemption()

        // Auto-start service on launch
        if (ConnectionService.serviceState.value == ConnectionService.State.IDLE) {
            startConnectionService()
        }

        setContent {
            DiLinkAutoTheme {
                ClientScreen(
                    onStartService = { startConnectionService() },
                    onStopService = { stopConnectionService() },
                    onInstallOnCar = { ip -> installOnCar(ip) },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onOpenNotificationAccess = { openNotificationSettings() },
                    onOpenDeveloperOptions = { openDeveloperOptions() }
                )
            }
        }
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopConnectionService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun installOnCar(ip: String? = null) {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_INSTALL_CAR
            if (!ip.isNullOrBlank()) putExtra("car_ip", ip)
        }
        startService(intent)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Battery exemption request failed: ${e.message}")
            }
        }
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
            } catch (_: Exception) {}
        }
    }
}

// ─── Theme ───

@Composable
fun DiLinkAutoTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF4FC3F7),
        onPrimary = Color.Black,
        secondary = Color(0xFF1A73E8),
        background = Color(0xFF0D1117),
        surface = Color(0xFF161B22),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

// ─── UI ───

@Composable
fun ClientScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onInstallOnCar: (String?) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDeveloperOptions: () -> Unit = {}
) {
    val serviceState by ConnectionService.serviceState.collectAsState()
    val installStatus by ConnectionService.installStatusFlow.collectAsState()
    val isRunning = serviceState != ConnectionService.State.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "DiLink Auto",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Phone Client",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(40.dp))

        StatusCard(serviceState)

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { if (isRunning) onStopService() else onStartService() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "Stop Service" else "Start Service",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(16.dp))

        // Install on Car
        var carIp by remember { mutableStateOf("") }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Install on Car", fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("Car must have ADB enabled (port 5555)", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = carIp,
                        onValueChange = { carIp = it },
                        label = { Text("Car IP address") },
                        placeholder = { Text("e.g. 192.168.43.100") },
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
                        onClick = { onInstallOnCar(carIp.trim().ifEmpty { null }) },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Install")
                    }
                }
                if (installStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(installStatus, fontSize = 13.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Required Permissions",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // Re-check permissions periodically (picks up changes when returning from settings)
        val context = androidx.compose.ui.platform.LocalContext.current
        var permissionsKey by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                permissionsKey++
            }
        }

        // Accessibility Service check
        val accessibilityEnabled = remember(permissionsKey) {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
        SetupItem(
            icon = if (accessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.TouchApp,
            title = if (accessibilityEnabled) "Accessibility Service ✓" else "Accessibility Service",
            description = if (accessibilityEnabled) "Enabled" else "Allows car touchscreen to control your phone",
            onClick = onOpenAccessibility
        )

        Spacer(Modifier.height(8.dp))

        // Notification Access check
        val notificationEnabled = remember(permissionsKey) {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(context.packageName)
        }
        SetupItem(
            icon = if (notificationEnabled) Icons.Default.CheckCircle else Icons.Default.Notifications,
            title = if (notificationEnabled) "Notification Access ✓" else "Notification Access",
            description = if (notificationEnabled) "Enabled" else "Forwards notifications to car display",
            onClick = onOpenNotificationAccess
        )

        Spacer(Modifier.height(8.dp))

        // USB Debugging reminder
        SetupItem(
            icon = Icons.Default.Usb,
            title = "USB Debugging",
            description = "Enable in Developer Options. Plug phone into car USB to connect.",
            onClick = onOpenDeveloperOptions
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun StatusCard(state: ConnectionService.State) {
    val ipAddresses = remember { getLocalIpAddresses() }

    val (color, title, subtitle) = when (state) {
        ConnectionService.State.IDLE -> Triple(
            Color(0xFF757575), "Service Stopped", "Tap Start to begin"
        )
        ConnectionService.State.WAITING -> Triple(
            Color(0xFFFFA726), "Waiting for Car",
            if (ipAddresses.isNotEmpty()) "Listening on: ${ipAddresses.joinToString(", ")}"
            else "Plug phone into car USB, then connect via WiFi"
        )
        ConnectionService.State.CONNECTED -> Triple(
            Color(0xFF2196F3), "Car Connected", "Waiting for virtual display…"
        )
        ConnectionService.State.STREAMING -> Triple(
            Color(0xFF4CAF50), "Streaming", "Virtual display active"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium, color = Color.White)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun SetupItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, color = Color.White)
                Text(description, fontSize = 12.sp, color = Color.Gray)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}


private fun getLocalIpAddresses(): List<String> {
    return try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    .map { "${it.hostAddress}:9637" }
            }
    } catch (_: Exception) {
        emptyList()
    }
}
