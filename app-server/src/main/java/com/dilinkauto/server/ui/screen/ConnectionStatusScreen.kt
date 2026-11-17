package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.R
import com.dilinkauto.server.data.ManualConnectState
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.SuccessColor
import com.dilinkauto.server.ui.theme.WarningColor

/**
 * Connection-status panel shown inside [HomeContent] when the link is not yet
 * streaming. Distinct from [CarLaunchScreen], which is the full pre-streaming
 * screen — this composable shares the [ManualConnectBox] with it.
 */
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
                        stringResource(R.string.searching_for_phone),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.phone_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // Manual connect option
                    Spacer(Modifier.height(32.dp))
                    ManualConnectBox(onConnect = onManualConnect)
                }
                CarConnectionService.State.CONNECTING -> {
                    CircularProgressIndicator(
                        color = WarningColor,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.connecting),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.CONNECTED -> {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = SuccessColor,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.connected_waiting_for_data),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.STREAMING -> {
                    // Won't reach here since HomeContent shows AppGrid when streaming
                }
            }
        }
    }
}

/**
 * Manual IP entry box. Shared by [ConnectionStatus] (inside HomeContent) and
 * [CarLaunchScreen]. Persists the last-used IP and pre-fills with the WiFi
 * gateway when no saved IP exists.
 */
@Composable
fun ManualConnectBox(onConnect: (String) -> Unit) {
    val context = LocalContext.current
    // Prefs + gateway probing live in the state holder (SRP-08); this box only
    // binds to its default and writes the last-used address back through it.
    val manualConnect = remember(context) { ManualConnectState(context) }
    var ipAddress by remember { mutableStateOf(manualConnect.defaultIp) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.manual_connect_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text(stringResource(R.string.manual_connect_ip_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Button(
                    onClick = {
                        val ip = ipAddress.trim()
                        if (ip.isNotBlank()) {
                            manualConnect.remember(ip)
                            onConnect(ip)
                        }
                    },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.manual_connect_button))
                }
            }
        }
    }
}
