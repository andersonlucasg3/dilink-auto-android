package com.dilinkauto.client

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.service.UpdateManager
import com.dilinkauto.client.service.UpdateState

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply() }

    private var showOnboarding = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOnboarding.value = !onboardingCompleted

        setContent {
            DiLinkAutoTheme {
                if (showOnboarding.value) {
                    OnboardingScreen(onComplete = {
                            onboardingCompleted = true
                            showOnboarding.value = false
                        }
                    )
                } else {
                    ClientScreen(
                        onStartService = { startConnectionService() },
                        onStopService = { stopConnectionService() },
                        onInstallOnCar = { ip -> installOnCar(ip) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenNotificationAccess = { openNotificationSettings() },
                        onOpenDeveloperOptions = { openDeveloperOptions() },
                        onCheckForUpdate = { UpdateManager.checkForUpdate(force = true) },
                        onDownloadUpdate = { UpdateManager.downloadUpdate() },
                        onInstallUpdate = { UpdateManager.installUpdate(this) }
                    )
                }
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

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Battery exemption request failed: ${e.message}")
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

    companion object {
        private const val PREFS_NAME = "dilinkauto_onboarding"
        private const val KEY_ONBOARDING_DONE = "has_completed_onboarding"
    }
}

// ─── Onboarding Screen ───

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val actionLabel: String,
    val isGranted: () -> Boolean,
    val onAction: () -> Unit
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val pkg = context.packageName

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var checkKey by remember { mutableIntStateOf(0) }

    // Re-evaluate permission checks each time we return from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasAllFiles = remember(checkKey) {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    }
    val hasBattery = remember(checkKey) {
        pm.isIgnoringBatteryOptimizations(pkg)
    }
    val hasAccessibility = remember(checkKey) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == pkg }
    }
    val hasNotifications = remember(checkKey) {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        flat.contains(pkg)
    }

    val steps = remember(hasAllFiles, hasBattery, hasAccessibility, hasNotifications) {
        listOf(
            OnboardingStep(
                icon = Icons.Default.CarRepair,
                title = "Welcome to DiLink-Auto",
                description = "Use your phone apps on your car's built-in screen. Let's set up a few permissions to make everything work.",
                actionLabel = "Continue",
                isGranted = { true },
                onAction = {}
            ),
            OnboardingStep(
                icon = Icons.Default.Folder,
                title = "All Files Access",
                description = "Deploys the virtual display server to your phone's storage. Without this, the car can't start screen mirroring.",
                actionLabel = "Grant",
                isGranted = { hasAllFiles },
                onAction = {
                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            ),
            OnboardingStep(
                icon = Icons.Default.BatterySaver,
                title = "Battery Optimization",
                description = "Keeps the connection alive when your screen is off. Without this, streaming stops whenever your phone sleeps.",
                actionLabel = "Grant",
                isGranted = { hasBattery },
                onAction = {
                    if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:$pkg")
                                }
                            )
                        } catch (_: Exception) {}
                    }
                }
            ),
            OnboardingStep(
                icon = Icons.Default.TouchApp,
                title = "Accessibility Service",
                description = "Lets the car's touchscreen control your phone. Without this, you'll see the screen but can't tap anything.",
                actionLabel = "Grant",
                isGranted = { hasAccessibility },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ),
            OnboardingStep(
                icon = Icons.Default.Notifications,
                title = "Notification Access",
                description = "Shows phone notifications on the car display. Without this, you'll miss calls and messages while driving.",
                actionLabel = "Grant",
                isGranted = { hasNotifications },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ),
            OnboardingStep(
                icon = Icons.Default.CheckCircle,
                title = "You're All Set",
                description = "Everything's ready. Plug your phone into the car's USB port to get started.",
                actionLabel = "Start Using DiLink-Auto",
                isGranted = { true },
                onAction = {}
            )
        )
    }

    val step = steps[currentStep]

    // Auto-advance if current permission is already granted (after returning from settings)
    LaunchedEffect(checkKey, currentStep) {
        if (currentStep < steps.lastIndex && step.isGranted()) {
            kotlinx.coroutines.delay(300)
            currentStep++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            steps.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentStep) 10.dp else 8.dp)
                        .background(
                            if (index <= currentStep) MaterialTheme.colorScheme.primary
                            else Color(0xFF30363D),
                            RoundedCornerShape(50)
                        )
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // Icon
        AnimatedContent(targetState = step.icon, label = "icon") { icon ->
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = if (step.isGranted() && currentStep > 0) Color(0xFF4CAF50)
                       else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(32.dp))

        // Title
        AnimatedContent(targetState = step.title, label = "title") { title ->
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(12.dp))

        // Description
        AnimatedContent(targetState = step.description, label = "desc") { desc ->
            Text(
                desc,
                fontSize = 15.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }

        if (currentStep > 0 && step.isGranted()) {
            Spacer(Modifier.height(8.dp))
            Text("Granted", fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(48.dp))

        // Action button
        if (currentStep < steps.lastIndex) {
            Button(
                onClick = {
                    if (step.isGranted()) {
                        currentStep++
                    } else {
                        step.onAction()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (step.isGranted()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (step.isGranted()) Icons.Default.CheckCircle else Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (step.isGranted()) "Continue" else step.actionLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Skip button (not for welcome step)
            if (currentStep > 0 && !step.isGranted()) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { currentStep++ }) {
                    Text("Skip for now", color = Color.Gray)
                }
            }
        } else {
            // Done step
            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text(step.actionLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
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
    onOpenDeveloperOptions: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {}
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

        Spacer(Modifier.height(16.dp))

        // App Update
        UpdateCard(
            onCheckForUpdate = onCheckForUpdate,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate
        )

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


@Composable
fun UpdateCard(
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val updateState by UpdateManager.updateState.collectAsState()
    val downloadProgress by UpdateManager.downloadProgress.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("App Update", fontWeight = FontWeight.Medium, color = Color.White)

            when (val state = updateState) {
                is UpdateState.Idle -> {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCheckForUpdate) {
                        Text("Check for Updates", color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UpdateState.Checking -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Checking for updates…", fontSize = 13.sp, color = Color.Gray)
                    }
                }
                is UpdateState.UpToDate -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Up to date (v${state.version})",
                        fontSize = 13.sp,
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCheckForUpdate) {
                        Text("Check Again", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                is UpdateState.Available -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "v${state.version} available",
                        fontSize = 13.sp,
                        color = Color(0xFFFFA726)
                    )
                    val sizeMb = state.sizeBytes / (1024.0 * 1024.0)
                    Text(
                        "${"%.1f".format(sizeMb)} MB",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onDownloadUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = downloadProgress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF30363D)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloading… ${downloadProgress}%",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "v${state.version} ready to install",
                        fontSize = 13.sp,
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install")
                    }
                }
                is UpdateState.Error -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.message,
                        fontSize = 12.sp,
                        color = Color(0xFFEF5350)
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCheckForUpdate) {
                        Text("Retry", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
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
