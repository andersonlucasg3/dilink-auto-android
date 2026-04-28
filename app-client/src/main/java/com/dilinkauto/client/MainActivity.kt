package com.dilinkauto.client

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.service.ConnectionService
import kotlinx.coroutines.launch
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

        // Prevent screen lock while streaming — HyperOS may override system timeout settings
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showOnboarding.value = !onboardingCompleted

        // Auto-start the service when the app is opened (e.g. by the car via USB ADB).
        // Only if onboarding is done and the service isn't already running — calling
        // startForegroundService on an already-running service is harmless but noisy.
        if (onboardingCompleted && ConnectionService.serviceState.value == ConnectionService.State.IDLE) {
            startConnectionService()
        }

        setContent {
            DiLinkAutoTheme {
                val installStatus by ConnectionService.installStatusFlow.collectAsState()
                if (showOnboarding.value) {
                    OnboardingScreen(
                        onComplete = {
                            onboardingCompleted = true
                            showOnboarding.value = false
                        },
                        onInstallOnCar = { installOnCar(null) },
                        installStatus = installStatus
                    )
                } else {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onOpenAllFilesAccess = { openAllFilesAccess() },
                            onOpenBatteryExemption = { openBatteryExemption() },
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenNotificationAccess = { openNotificationSettings() },
                            onOpenDeveloperOptions = { openDeveloperOptions() },
                            onCheckForUpdate = { UpdateManager.checkForUpdate(force = true) },
                            onDownloadUpdate = { UpdateManager.downloadUpdate() },
                            onInstallUpdate = { UpdateManager.installUpdate(this) }
                        )
                    } else {
                        MainScreen(
                            onStartService = { startConnectionService() },
                            onStopService = { stopConnectionService() },
                            onInstallOnCar = { ip -> installOnCar(ip) },
                            onOpenSettings = { showSettings = true },
                            onShareLogs = { shareLogs() }
                        )
                    }
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

    private fun shareLogs() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val zipFile = FileLog.zipLogs()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (zipFile != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        zipFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share_logs_title)))
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.share_logs_no_logs),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
    val onAction: () -> Unit,
    val prerequisites: List<String> = emptyList()
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit, onInstallOnCar: () -> Unit, installStatus: String) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val pkg = context.packageName
    val scope = rememberCoroutineScope()

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Re-check permissions instantly when returning from settings (handles both
    // full activities like Accessibility and dialogs like Battery Optimization)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Direct reads — no remember(), so they re-evaluate on every recomposition
    val hasAllFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    val hasBattery = pm.isIgnoringBatteryOptimizations(pkg)
    val hasAccessibility = run {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == pkg }
    }
    val hasNotifications = run {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        flat.contains(pkg)
    }

    // Poll a specific permission directly from the system API (bypasses any caching)
    fun pollPermission(stepIndex: Int) {
        scope.launch {
            for (i in 0..30) {
                kotlinx.coroutines.delay(300)
                val granted = when (stepIndex) {
                    1 -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
                    2 -> pm.isIgnoringBatteryOptimizations(pkg)
                    3 -> {
                        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                            .any { it.resolveInfo.serviceInfo.packageName == pkg }
                    }
                    4 -> {
                        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
                        flat.contains(pkg)
                    }
                    else -> true
                }
                if (granted) {
                    refreshKey++
                    break
                }
            }
        }
    }

    // Resolve strings outside remember to avoid crossinline restriction
    val welcomeTitle = stringResource(R.string.onboarding_welcome_title)
    val welcomeDesc = stringResource(R.string.onboarding_welcome_desc)
    val welcomeAction = stringResource(R.string.onboarding_continue)
    val filesTitle = stringResource(R.string.onboarding_files_title)
    val filesDesc = stringResource(R.string.onboarding_files_desc)
    val batteryTitle = stringResource(R.string.onboarding_battery_title)
    val batteryDesc = stringResource(R.string.onboarding_battery_desc)
    val accessibilityTitle = stringResource(R.string.onboarding_accessibility_title)
    val accessibilityDesc = stringResource(R.string.onboarding_accessibility_desc)
    val notificationTitle = stringResource(R.string.onboarding_notification_title)
    val notificationDesc = stringResource(R.string.onboarding_notification_desc)
    val carSetupTitle = stringResource(R.string.onboarding_car_setup_title)
    val carSetupDesc = stringResource(R.string.onboarding_car_setup_desc)
    val carSetupContinue = stringResource(R.string.onboarding_continue)
    val carInstallBtn = stringResource(R.string.onboarding_car_install_btn)
    val carSkipBtn = stringResource(R.string.onboarding_skip_btn)
    val carPrereqWifiAdb = stringResource(R.string.onboarding_car_prereq_wifi_adb)
    val carPrereqHotspot = stringResource(R.string.onboarding_car_prereq_hotspot)
    val carPrereqConnected = stringResource(R.string.onboarding_car_prereq_connected)
    val carPrereqInstalled = stringResource(R.string.onboarding_car_prereq_installed)
    val doneTitle = stringResource(R.string.onboarding_done_title)
    val doneDesc = stringResource(R.string.onboarding_done_desc)
    val doneAction = stringResource(R.string.onboarding_start)
    val grantLabel = stringResource(R.string.onboarding_grant)

    val steps = remember(hasAllFiles, hasBattery, hasAccessibility, hasNotifications, refreshKey) {
        listOf(
            OnboardingStep(
                icon = Icons.Default.CarRepair,
                title = welcomeTitle, description = welcomeDesc,
                actionLabel = welcomeAction,
                isGranted = { true }, onAction = {}
            ),
            OnboardingStep(
                icon = Icons.Default.Folder,
                title = filesTitle, description = filesDesc,
                actionLabel = grantLabel,
                isGranted = { hasAllFiles },
                onAction = {
                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            ),
            OnboardingStep(
                icon = Icons.Default.BatterySaver,
                title = batteryTitle, description = batteryDesc,
                actionLabel = grantLabel,
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
                title = accessibilityTitle, description = accessibilityDesc,
                actionLabel = grantLabel,
                isGranted = { hasAccessibility },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ),
            OnboardingStep(
                icon = Icons.Default.Notifications,
                title = notificationTitle, description = notificationDesc,
                actionLabel = grantLabel,
                isGranted = { hasNotifications },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ),
            OnboardingStep(
                icon = Icons.Default.DirectionsCar,
                title = carSetupTitle, description = carSetupDesc,
                actionLabel = carSetupContinue,
                isGranted = { true }, onAction = {},
                prerequisites = listOf(carPrereqWifiAdb, carPrereqHotspot, carPrereqConnected, carPrereqInstalled)
            ),
            OnboardingStep(
                icon = Icons.Default.CheckCircle,
                title = doneTitle, description = doneDesc,
                actionLabel = doneAction,
                isGranted = { true }, onAction = {}
            )
        )
    }

    val step = steps[currentStep]

    // Auto-advance if current permission is already granted (skip welcome and car setup steps)
    LaunchedEffect(refreshKey, currentStep) {
        if (currentStep > 0 && currentStep != 5 && currentStep < steps.lastIndex && step.isGranted()) {
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

        if (currentStep > 0 && currentStep != 5 && step.isGranted()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_granted_label), fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
        }

        // Car setup step: prerequisites + install button + skip
        if (currentStep == 5) {
            Spacer(Modifier.height(16.dp))

            // Prerequisite items
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                step.prerequisites.forEachIndexed { index, prereq ->
                    val icon = when (index) {
                        0 -> Icons.Default.Build
                        1 -> Icons.Default.Wifi
                        2 -> Icons.Default.Link
                        3 -> Icons.Default.Download
                        else -> Icons.Default.CheckCircle
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Color(0xFF8AB4F8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            prereq,
                            fontSize = 14.sp,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            val isInstalling = installStatus.isNotEmpty() &&
                !installStatus.contains("Success", ignoreCase = true) &&
                !installStatus.contains("installed", ignoreCase = true) &&
                !installStatus.contains("up-to-date", ignoreCase = true) &&
                !installStatus.contains("Error", ignoreCase = true) &&
                !installStatus.contains("Failed", ignoreCase = true) &&
                !installStatus.contains("not found", ignoreCase = true)

            val isDone = installStatus.contains("Success", ignoreCase = true) ||
                installStatus.contains("installed", ignoreCase = true) ||
                installStatus.contains("up-to-date", ignoreCase = true)

            val isError = installStatus.contains("Error", ignoreCase = true) ||
                installStatus.contains("Failed", ignoreCase = true) ||
                installStatus.contains("not found", ignoreCase = true)

            if (isInstalling) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
                ) {
                    InstallStageProgress(installStatus)
                }
                Spacer(Modifier.height(8.dp))
                Text(carSkipBtn, fontSize = 13.sp, color = Color.Gray)
            } else if (isDone) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(installStatus, fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
            } else if (isError) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(installStatus, fontSize = 14.sp, color = Color(0xFFEF5350))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onInstallOnCar,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick = onInstallOnCar,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(carInstallBtn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (!isInstalling) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { currentStep++ }) {
                    Text(carSkipBtn, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(16.dp))
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
                        pollPermission(currentStep)
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
                    Text(stringResource(R.string.onboarding_skip_btn), color = Color.Gray)
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

// ─── Main Screen ───

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onInstallOnCar: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onShareLogs: () -> Unit
) {
    val serviceState by ConnectionService.serviceState.collectAsState()
    val installStatus by ConnectionService.installStatusFlow.collectAsState()
    val isRunning = serviceState != ConnectionService.State.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Fixed header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.main_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.main_subtitle), fontSize = 14.sp, color = Color.Gray)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
            }
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

        // Service status
        StatusCard(serviceState)

        Spacer(Modifier.height(12.dp))

        // Start/Stop
        Button(
            onClick = { if (isRunning) onStopService() else onStartService() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isRunning) stringResource(R.string.stop_service) else stringResource(R.string.start_service), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // Install on Car (unified: button + status)
        CarInstallCard(
            installStatus = installStatus,
            onInstallOnCar = onInstallOnCar
        )

        Spacer(Modifier.height(24.dp))

        // Support / Donations
        DonationCard()

        // Share Logs
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_logs_title), fontWeight = FontWeight.Medium, color = Color.White)
                    Text(stringResource(R.string.share_logs_desc), fontSize = 12.sp, color = Color.Gray)
                }
                Button(
                    onClick = onShareLogs,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text(stringResource(R.string.share_logs_button), fontSize = 13.sp)
                }
            }
        }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Settings Screen ───

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenBatteryExemption: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val pkg = context.packageName
    var permissionsKey by remember { mutableIntStateOf(0) }

    // Periodic re-check
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            permissionsKey++
        }
    }

    // Permission checks
    val hasAllFiles = remember(permissionsKey) {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    }
    val hasBattery = remember(permissionsKey) {
        pm.isIgnoringBatteryOptimizations(pkg)
    }
    val hasAccessibility = remember(permissionsKey) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == pkg }
    }
    val hasNotifications = remember(permissionsKey) {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        flat.contains(pkg)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Fixed header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

        // Permissions
        Text("Permissions", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp))

        SetupItem(
            icon = if (hasAllFiles) Icons.Default.CheckCircle else Icons.Default.Folder,
            title = if (hasAllFiles) "All Files Access ✓" else "All Files Access",
            description = if (hasAllFiles) "Granted" else "Needed to deploy the virtual display server",
            onClick = onOpenAllFilesAccess
        )
        Spacer(Modifier.height(8.dp))

        SetupItem(
            icon = if (hasBattery) Icons.Default.CheckCircle else Icons.Default.BatterySaver,
            title = if (hasBattery) "Battery Optimization ✓" else "Battery Optimization",
            description = if (hasBattery) "Granted" else "Keeps streaming alive when screen is off",
            onClick = onOpenBatteryExemption
        )
        Spacer(Modifier.height(8.dp))

        SetupItem(
            icon = if (hasAccessibility) Icons.Default.CheckCircle else Icons.Default.TouchApp,
            title = if (hasAccessibility) "Accessibility Service ✓" else "Accessibility Service",
            description = if (hasAccessibility) "Granted" else "Enables car touchscreen control",
            onClick = onOpenAccessibility
        )
        Spacer(Modifier.height(8.dp))

        SetupItem(
            icon = if (hasNotifications) Icons.Default.CheckCircle else Icons.Default.Notifications,
            title = if (hasNotifications) "Notification Access ✓" else "Notification Access",
            description = if (hasNotifications) "Granted" else "Forwards notifications to car display",
            onClick = onOpenNotificationAccess
        )

        Spacer(Modifier.height(8.dp))

        SetupItem(
            icon = Icons.Default.Usb,
            title = "USB Debugging",
            description = "Required on both phone and car",
            onClick = onOpenDeveloperOptions
        )

        Spacer(Modifier.height(32.dp))

        // Updates
        Text("Updates", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp))

        UpdatesCard(
            onCheckForUpdate = onCheckForUpdate,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate
        )

        Spacer(Modifier.height(32.dp))

        // About
        Text("About", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                } catch (_: Exception) { "unknown" }
                @Suppress("DEPRECATION")
                val versionCode = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                } catch (_: Exception) { 0 }
                Text("DiLink-Auto v$versionName (build $versionCode)", fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("Open-source alternative to Android Auto", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_dev_credit), fontSize = 12.sp, color = Color(0xFFB0BEC5))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andersonlucasg3"))
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.about_dev_github), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Open Source Libraries:", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB0BEC5))
                Text("dadb — Apache 2.0", fontSize = 12.sp, color = Color.Gray)
                Text("Jetpack Compose — Apache 2.0", fontSize = 12.sp, color = Color.Gray)
                Text("kotlinx-coroutines — Apache 2.0", fontSize = 12.sp, color = Color.Gray)
                Text("scrcpy (FakeContext/SurfaceScaler concepts) — Apache 2.0", fontSize = 12.sp, color = Color.Gray)
            }
        }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatusCard(state: ConnectionService.State) {
    val ipAddresses = remember { getLocalIpAddresses() }

    val (color, title, subtitle) = when (state) {
        ConnectionService.State.IDLE -> Triple(
            Color(0xFF757575), stringResource(R.string.status_stopped), stringResource(R.string.status_stopped_desc)
        )
        ConnectionService.State.WAITING -> Triple(
            Color(0xFFFFA726), stringResource(R.string.status_waiting),
            if (ipAddresses.isNotEmpty()) stringResource(R.string.status_listening, ipAddresses.joinToString(", "))
            else stringResource(R.string.status_waiting_desc)
        )
        ConnectionService.State.CONNECTED -> Triple(
            Color(0xFF2196F3), stringResource(R.string.status_connected), stringResource(R.string.status_connected_desc)
        )
        ConnectionService.State.STREAMING -> Triple(
            Color(0xFF4CAF50), stringResource(R.string.status_streaming), stringResource(R.string.status_streaming_desc)
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
fun UpdatesCard(
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
            Text("Updates", fontWeight = FontWeight.Medium, color = Color.White)

            // ── Self-update status ──
            Spacer(Modifier.height(8.dp))
            when (val state = updateState) {
                is UpdateState.Idle -> {
                    TextButton(onClick = onCheckForUpdate) {
                        Text("Check for phone update", color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking for updates…", fontSize = 13.sp, color = Color.Gray)
                    }
                }
                is UpdateState.UpToDate -> {
                    Text("Phone: up to date (v${state.version})", fontSize = 13.sp, color = Color(0xFF4CAF50))
                }
                is UpdateState.Available -> {
                    Text("Phone: v${state.version} available", fontSize = 13.sp, color = Color(0xFFFFA726))
                    val sizeMb = state.sizeBytes / (1024.0 * 1024.0)
                    Text("${"%.1f".format(sizeMb)} MB", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownloadUpdate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download Update")
                    }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = downloadProgress / 100f, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF30363D))
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading… ${downloadProgress}%", fontSize = 13.sp, color = Color.Gray)
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Phone: v${state.version} ready", fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstallUpdate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install Update")
                    }
                }
                is UpdateState.Error -> {
                    Text(state.message, fontSize = 12.sp, color = Color(0xFFEF5350))
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCheckForUpdate) { Text("Retry", color = MaterialTheme.colorScheme.primary) }
                }
            }

        }
    }
}

@Composable
fun CarInstallCard(installStatus: String, onInstallOnCar: (String?) -> Unit) {
    val context = LocalContext.current

    val isInstalling = installStatus.isNotEmpty() &&
        !installStatus.contains("Success", ignoreCase = true) &&
        !installStatus.contains("installed", ignoreCase = true) &&
        !installStatus.contains("up-to-date", ignoreCase = true) &&
        !installStatus.contains("Error", ignoreCase = true) &&
        !installStatus.contains("Failed", ignoreCase = true) &&
        !installStatus.contains("not found", ignoreCase = true)

    val isDone = installStatus.contains("Success", ignoreCase = true) ||
        installStatus.contains("installed", ignoreCase = true) ||
        installStatus.contains("up-to-date", ignoreCase = true)

    val isError = installStatus.contains("Error", ignoreCase = true) ||
        installStatus.contains("Failed", ignoreCase = true) ||
        installStatus.contains("not found", ignoreCase = true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = if (isDone) Color(0xFF4CAF50) else if (isError) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Car App", fontWeight = FontWeight.Medium, color = Color.White)
                    Text(
                        if (installStatus.isEmpty()) "Install or update the car app over WiFi ADB" else installStatus,
                        fontSize = 12.sp,
                        color = when {
                            isDone -> Color(0xFF4CAF50)
                            isError -> Color(0xFFEF5350)
                            isInstalling -> Color(0xFFFFA726)
                            else -> Color.Gray
                        }
                    )
                }
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFFFA726)
                    )
                } else if (isDone) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = { onInstallOnCar(null) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(if (isError) "Retry" else "Install", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DonationCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Support Development", fontWeight = FontWeight.Medium, color = Color.White)
            Text(
                "Help keep DiLink-Auto going. Every contribution helps!",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sponsors/andersonlucasg3"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E40C9))
                ) {
                    Text("GitHub Sponsor", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nubank.com.br/cobrar/5gf35/69ed4939-b2c0-4071-b75d-3b430ab70a5d"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C2A0))
                ) {
                    Text("Pix (Brazil)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun InstallStatusCard(status: String) {
    val (_, color, isInProgress) = when {
        status.contains("Searching", ignoreCase = true) -> Triple(Icons.Default.Search, Color(0xFFFFA726), true)
        status.contains("Connecting", ignoreCase = true) -> Triple(Icons.Default.Usb, Color(0xFFFFA726), true)
        status.contains("Push", ignoreCase = true) -> Triple(Icons.Default.Download, Color(0xFF2196F3), true)
        status.contains("Install", ignoreCase = true) -> Triple(Icons.Default.InstallMobile, Color(0xFF2196F3), true)
        status.contains("Launching", ignoreCase = true) -> Triple(Icons.Default.PlayArrow, Color(0xFF4CAF50), true)
        status.contains("Checking", ignoreCase = true) -> Triple(Icons.Default.Info, Color(0xFFFFA726), true)
        status.contains("Success", ignoreCase = true) || status.contains("installed", ignoreCase = true) || status.contains("up-to-date", ignoreCase = true) -> Triple(Icons.Default.CheckCircle, Color(0xFF4CAF50), false)
        status.contains("Error", ignoreCase = true) || status.contains("Failed", ignoreCase = true) || status.contains("not found", ignoreCase = true) -> Triple(Icons.Default.Warning, Color(0xFFEF5350), false)
        else -> Triple(Icons.Default.Info, Color(0xFF757575), true)
    }

    val isDone = !isInProgress && color == Color(0xFF4CAF50)
    val isError = !isInProgress && color == Color(0xFFEF5350)
    val isActive = isInProgress

    // Determine current stage index for progress visualization
    val stageLabels = listOf(
        "Searching" to "Searching for car",
        "Connecting" to "Connecting to car",
        "Checking" to "Checking version",
        "Push" to "Pushing APK",
        "Install" to "Installing",
        "Launching" to "Launching"
    )
    val currentStage = stageLabels.indexOfLast { status.contains(it.first, ignoreCase = true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = when {
                        isDone -> Color(0xFF4CAF50)
                        isError -> Color(0xFFEF5350)
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isDone -> "Car App Installed"
                        isError -> "Installation Failed"
                        isActive -> "Installing on Car"
                        else -> "Car App"
                    },
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }

            // Stage progress
            if (isActive && currentStage >= 0) {
                Spacer(Modifier.height(12.dp))
                stageLabels.forEachIndexed { index, (_, label) ->
                    val stageState = when {
                        index < currentStage -> "done"
                        index == currentStage -> "active"
                        else -> "pending"
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            when (stageState) {
                                "done" -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                "active" -> CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary)
                                "pending" -> Box(modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF30363D), RoundedCornerShape(4.dp)))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = when (stageState) {
                                "done" -> Color(0xFF4CAF50)
                                "active" -> Color.White
                                else -> Color(0xFF757575)
                            }
                        )
                    }
                }
            } else if (isDone || isError) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isDone) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isDone) Color(0xFF4CAF50) else Color(0xFFEF5350),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(status, fontSize = 13.sp, color = if (isDone) Color(0xFF4CAF50) else Color(0xFFEF5350))
                }
            }
        }
    }
}

@Composable
fun InstallStageProgress(status: String) {
    val stageLabels = listOf(
        "Searching" to "Searching for car",
        "Connecting" to "Connecting to car",
        "Checking" to "Checking version",
        "Push" to "Pushing APK",
        "Install" to "Installing",
        "Launching" to "Launching"
    )
    val currentStage = stageLabels.indexOfLast { status.contains(it.first, ignoreCase = true) }

    Column(modifier = Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(status, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
        if (currentStage >= 0) {
            Spacer(Modifier.height(10.dp))
            stageLabels.forEachIndexed { index, (_, label) ->
                val stageState = when {
                    index < currentStage -> "done"
                    index == currentStage -> "active"
                    else -> "pending"
                }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        when (stageState) {
                            "done" -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(12.dp))
                            "active" -> CircularProgressIndicator(
                                modifier = Modifier.size(12.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary)
                            "pending" -> Box(modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF30363D), RoundedCornerShape(3.dp)))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = when (stageState) {
                            "done" -> Color(0xFF4CAF50)
                            "active" -> Color.White
                            else -> Color(0xFF757575)
                        }
                    )
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
