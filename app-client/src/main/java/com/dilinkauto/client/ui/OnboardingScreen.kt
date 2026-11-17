package com.dilinkauto.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dilinkauto.client.R
import kotlinx.coroutines.delay

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val actionLabel: String,
    val isGranted: () -> Boolean,
    val onAction: () -> Unit,
    val prerequisites: List<String> = emptyList()
)

/**
 * Onboarding flow: welcome, all-files access, battery exemption, accessibility,
 * car setup, done. Re-checks permissions on resume and polls while waiting.
 *
 * Step/polling state lives in [OnboardingState]; the install-status section
 * lives in [CarSetupInstallSection] (audit R3-SRP-05).
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, onInstallOnCar: () -> Unit, installStatus: String) {
    val context = LocalContext.current
    val pkg = context.packageName
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(saver = OnboardingState.Saver) { OnboardingState() }
    val currentStep = state.currentStep
    val refreshKey = state.refreshKey

    // Re-check permissions instantly when returning from settings (handles both
    // full activities like Accessibility and dialogs like Battery Optimization)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.recheckPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Direct reads — no remember(), so they re-evaluate on every recomposition
    val hasAllFiles = PermissionChecker.hasAllFilesAccess()
    val hasBattery = PermissionChecker.hasBatteryExemption(context, pkg)
    val hasAccessibility = PermissionChecker.hasAccessibility(context, pkg)

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

    val steps = remember(hasAllFiles, hasBattery, hasAccessibility, refreshKey) {
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
                    if (!PermissionChecker.hasAllFilesAccess()) PermissionIntents.openAllFilesAccess(context)
                }
            ),
            OnboardingStep(
                icon = Icons.Default.BatterySaver,
                title = batteryTitle, description = batteryDesc,
                actionLabel = grantLabel,
                isGranted = { hasBattery },
                onAction = {
                    if (!PermissionChecker.hasBatteryExemption(context, pkg)) PermissionIntents.openBatteryExemption(context)
                }
            ),
            OnboardingStep(
                icon = Icons.Default.TouchApp,
                title = accessibilityTitle, description = accessibilityDesc,
                actionLabel = grantLabel,
                isGranted = { hasAccessibility },
                onAction = {
                    PermissionIntents.openAccessibilitySettings(context)
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
        if (currentStep > 0 && currentStep != 4 && currentStep < steps.lastIndex && step.isGranted()) {
            delay(300)
            state.next()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                tint = if (step.isGranted() && currentStep > 0) InstallStatusVisuals.DoneColor
                       else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(32.dp))

        // Title
        AnimatedContent(targetState = step.title, label = "title") { title ->
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(12.dp))

        // Description
        AnimatedContent(targetState = step.description, label = "desc") { desc ->
            Text(
                desc,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }

        if (currentStep > 0 && currentStep != 4 && step.isGranted()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_granted_label), style = MaterialTheme.typography.labelLarge, color = InstallStatusVisuals.DoneColor)
        }

        // Car setup step: prerequisites + install button + skip
        if (currentStep == 4) {
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            CarSetupInstallSection(
                installStatus = installStatus,
                onInstallOnCar = onInstallOnCar,
                onSkip = { state.next() },
                carInstallBtn = carInstallBtn,
                carSkipBtn = carSkipBtn
            )

            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(48.dp))

        // Action button
        if (currentStep < steps.lastIndex) {
            Button(
                onClick = {
                    if (step.isGranted()) {
                        state.next()
                    } else {
                        step.onAction()
                        state.pollPermission(context, scope, currentStep)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (step.isGranted()) InstallStatusVisuals.DoneColor else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (step.isGranted()) Icons.Default.CheckCircle else Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (step.isGranted()) stringResource(R.string.onboarding_continue) else step.actionLabel,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // Skip button (not for welcome step)
            if (currentStep > 0 && !step.isGranted()) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { state.next() }) {
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
                    containerColor = InstallStatusVisuals.DoneColor
                )
            ) {
                Text(step.actionLabel, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
