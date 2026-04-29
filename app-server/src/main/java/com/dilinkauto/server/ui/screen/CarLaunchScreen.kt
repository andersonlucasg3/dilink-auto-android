package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService

/**
 * Full-screen launch / connection screen — no nav bar, connection-focused.
 *
 * Shown when the car app launches before the phone connection is established
 * and app icons are received. Once connected and app icons arrive, the UI
 * transitions to the streaming mode with nav bar, home, and apps.
 */
@Composable
fun CarLaunchScreen(service: CarConnectionService) {
    val state by service.state.collectAsState()
    val phoneName by service.phoneName.collectAsState()
    val statusMessage by service.statusMessage.collectAsState()
    var devMode by remember { mutableStateOf(service.devMode) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            val isWide = maxWidth > 700.dp

            if (isWide) {
                // Two-column layout for wide screens (car display)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(48.dp)
                ) {
                    // Left: branding + instructions
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        BrandingSection()

                        if (state != CarConnectionService.State.STREAMING &&
                            state != CarConnectionService.State.CONNECTED) {
                            Spacer(Modifier.height(48.dp))
                            HowToConnect()
                        }
                    }

                    // Right: status + action
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ConnectionStatusCard(
                            state = state,
                            phoneName = phoneName,
                            statusMessage = statusMessage,
                            devMode = devMode,
                            onDevModeChange = { newValue ->
                                devMode = newValue
                                service.devMode = newValue
                            }
                        )

                        if (devMode && (state == CarConnectionService.State.IDLE ||
                                    state == CarConnectionService.State.CONNECTING)) {
                            Spacer(Modifier.height(16.dp))
                            WifiAdbSetupCard()
                        }

                        if (state != CarConnectionService.State.STREAMING &&
                            state != CarConnectionService.State.CONNECTED) {
                            Spacer(Modifier.height(24.dp))
                            ManualConnectBox(onConnect = { ip -> service.connectManual(ip) })
                        }
                    }
                }
            } else {
                // Single-column layout for narrow screens
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 560.dp)
                ) {
                    BrandingSection()
                    Spacer(Modifier.height(32.dp))
                    ConnectionStatusCard(
                        state = state,
                        phoneName = phoneName,
                        statusMessage = statusMessage,
                        devMode = devMode,
                        onDevModeChange = { newValue ->
                            devMode = newValue
                            service.devMode = newValue
                        }
                    )

                    if (devMode && (state == CarConnectionService.State.IDLE ||
                                state == CarConnectionService.State.CONNECTING)) {
                        Spacer(Modifier.height(12.dp))
                        WifiAdbSetupCard()
                    }

                    if (state != CarConnectionService.State.STREAMING &&
                        state != CarConnectionService.State.CONNECTED) {
                        Spacer(Modifier.height(20.dp))
                        HowToConnect()

                        Spacer(Modifier.height(20.dp))
                        ManualConnectBox(onConnect = { ip -> service.connectManual(ip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandingSection() {
    Icon(
        Icons.Default.PhoneAndroid,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.branding_title),
        style = MaterialTheme.typography.headlineLarge,
        color = Color.White,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.branding_tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Gray
    )
}

/**
 * Connection status card with integrated WiFi/USB mode selector.
 * Shows connection indicator, status text, phone name, and the
 * connection mode toggle when not yet streaming.
 */
@Composable
private fun ConnectionStatusCard(
    state: CarConnectionService.State,
    phoneName: String,
    statusMessage: String,
    devMode: Boolean,
    onDevModeChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            when (state) {
                                CarConnectionService.State.STREAMING -> Color(0xFF4CAF50)
                                CarConnectionService.State.CONNECTED -> Color(0xFFFFA726)
                                CarConnectionService.State.CONNECTING -> Color(0xFFFFA726)
                                else -> Color(0xFF757575)
                            },
                            RoundedCornerShape(6.dp)
                        )
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            CarConnectionService.State.IDLE -> stringResource(R.string.status_ready_to_connect)
                            CarConnectionService.State.CONNECTING -> stringResource(R.string.status_connecting)
                            CarConnectionService.State.CONNECTED -> stringResource(R.string.status_connected)
                            CarConnectionService.State.STREAMING -> stringResource(R.string.status_streaming)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    if (statusMessage.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (phoneName.isNotEmpty()) phoneName else statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
                if (state == CarConnectionService.State.CONNECTING ||
                    state == CarConnectionService.State.IDLE
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // Connection mode selector — always visible when not streaming
            if (state != CarConnectionService.State.STREAMING &&
                state != CarConnectionService.State.CONNECTED) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dev_mode_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (devMode) stringResource(R.string.dev_mode_desc_on)
                            else stringResource(R.string.dev_mode_desc_off),
                            color = if (devMode) Color(0xFFFFA726) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = devMode,
                        onCheckedChange = onDevModeChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFFFFA726)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiAdbSetupCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.wifi_adb_setup_title),
                color = Color(0xFFFFA726),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wifi_adb_setup_desc),
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun HowToConnect() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.how_to_connect_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(16.dp))
            ConnectStep("1", stringResource(R.string.how_to_step_1))
            Spacer(Modifier.height(12.dp))
            ConnectStep("2", stringResource(R.string.how_to_step_2))
            Spacer(Modifier.height(12.dp))
            ConnectStep("3", stringResource(R.string.how_to_step_3))
            Spacer(Modifier.height(12.dp))
            ConnectStep("4", stringResource(R.string.how_to_step_4))
        }
    }
}

@Composable
private fun ConnectStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFBBBBBB)
        )
    }
}
