package com.dilinkauto.client.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.R
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.service.InstallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main phone screen: status, start/stop, car-install card, share-logs.
 */
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
    val isRunning = serviceState != com.dilinkauto.client.service.SessionState.IDLE
    val isSamsung = remember { android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
    var samsungWarningDismissed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                Text(stringResource(R.string.main_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.main_subtitle), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings), tint = Color.Gray)
            }
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Samsung device warning
            if (isSamsung && !samsungWarningDismissed) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332211))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.samsung_warning_title), fontWeight = FontWeight.Medium, color = Color.White)
                                Text(stringResource(R.string.samsung_warning_desc), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onOpenSettings) {
                                Text(stringResource(R.string.samsung_settings_guide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { samsungWarningDismissed = true }) {
                                Text(stringResource(R.string.onboarding_skip_btn), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Service status
            StatusCard(serviceState)

            // Card-to-card gap is 24dp: larger than the 16–20dp inside each card,
            // so adjacent cards read as separate groups (audit UX-14).
            Spacer(Modifier.height(24.dp))

            // Start/Stop
            Button(
                onClick = { if (isRunning) onStopService() else onStartService() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                    // The default content colour is onPrimary — black here, which on
                    // #D32F2F measures 4.22:1. White measures 4.98:1 (audit UX-05).
                    contentColor = if (isRunning) Color.White else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isRunning) stringResource(R.string.stop_service) else stringResource(R.string.start_service), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(24.dp))

            // Install on Car (unified: button + status)
            CarInstallCard(
                installStatus = installStatus,
                onInstallOnCar = onInstallOnCar
            )

            Spacer(Modifier.height(24.dp))

            // Share Logs
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
                        Text(stringResource(R.string.share_logs_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Button(
                        onClick = onShareLogs,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3),
                            // ButtonDefaults keeps onPrimary (black here) as the default
                            // content colour, which measures 6.72:1 on this blue and is
                            // left as-is — stated explicitly so it is not mistaken for
                            // an oversight next time this colour is touched.
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.share_logs_button), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatusCard(state: com.dilinkauto.client.service.SessionState) {
    // NetworkInterface enumeration is a blocking syscall — off the composition
    // thread (audit A-L22), mirroring AllowlistScreen's load pattern: state
    // starts empty and LaunchedEffect fills it on IO.
    var ipAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        ipAddresses = withContext(Dispatchers.IO) { getLocalIpAddresses() }
    }

    val (color, title, subtitle) = when (state) {
        com.dilinkauto.client.service.SessionState.IDLE -> Triple(
            Color(0xFF757575), stringResource(R.string.status_stopped), stringResource(R.string.status_stopped_desc)
        )
        com.dilinkauto.client.service.SessionState.WAITING -> Triple(
            Color(0xFFFFA726), stringResource(R.string.status_waiting),
            if (ipAddresses.isNotEmpty()) stringResource(R.string.status_listening, ipAddresses.joinToString(", "))
            else stringResource(R.string.status_waiting_desc)
        )
        com.dilinkauto.client.service.SessionState.CONNECTED -> Triple(
            Color(0xFF2196F3), stringResource(R.string.status_connected), stringResource(R.string.status_connected_desc)
        )
        com.dilinkauto.client.service.SessionState.STREAMING -> Triple(
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
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun CarInstallCard(installStatus: String, onInstallOnCar: (String?) -> Unit) {
    val status = InstallStatus.parse(installStatus)
    val isDone = status == InstallStatus.DONE
    val isError = status == InstallStatus.ERROR
    val isAuthNeeded = status == InstallStatus.AUTH_NEEDED
    val isInstalling = status.isInProgress

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
                    tint = if (isDone) InstallStatusVisuals.DoneColor else if (isError || isAuthNeeded) InstallStatusVisuals.AttentionColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.car_app_title), fontWeight = FontWeight.Medium, color = Color.White)
                    Text(
                        if (installStatus.isEmpty()) stringResource(R.string.car_app_desc) else installStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = InstallStatusVisuals.statusColor(status) ?: Color.Gray
                    )
                }
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = InstallStatusVisuals.AttentionColor
                    )
                } else if (isDone) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = InstallStatusVisuals.DoneColor, modifier = Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = { onInstallOnCar(null) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // One word for one action: the retry label is the same here
                        // and in the onboarding car-setup step, and the install label
                        // matches the onboarding button (audit UX-15).
                        Text(
                            if (isError || isAuthNeeded) stringResource(R.string.car_app_retry)
                            else stringResource(R.string.install_on_car),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
