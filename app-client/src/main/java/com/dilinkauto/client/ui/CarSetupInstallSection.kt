package com.dilinkauto.client.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.R
import com.dilinkauto.client.service.InstallStatus

/**
 * The car-setup step's install-status section, extracted from OnboardingScreen
 * (audit R3-SRP-05). Shows the live install status from
 * [com.dilinkauto.client.service.ConnectionService.installStatusFlow] with the
 * appropriate action button (install / retry on auth / retry on error / skip
 * while installing).
 *
 * Status colors come from [InstallStatusVisuals] — shared with the main
 * screen's install card (audit R3-DRY-10).
 */
@Composable
internal fun CarSetupInstallSection(
    installStatus: String,
    onInstallOnCar: () -> Unit,
    onSkip: () -> Unit,
    carInstallBtn: String,
    carSkipBtn: String
) {
    val status = InstallStatus.parse(installStatus)
    val stageIndex = InstallStatus.stageIndex(installStatus)

    when (status) {
        InstallStatus.DONE -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = InstallStatusVisuals.DoneColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, style = MaterialTheme.typography.labelLarge, color = InstallStatusVisuals.DoneColor)
            }
            Spacer(Modifier.height(8.dp))
        }
        InstallStatus.AUTH_NEEDED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = InstallStatusVisuals.AttentionColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, style = MaterialTheme.typography.bodySmall, color = InstallStatusVisuals.AttentionColor)
            }
            Spacer(Modifier.height(8.dp))
            InstallRetryButton(onInstallOnCar, stringResource(R.string.car_app_retry))
        }
        InstallStatus.ERROR -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = InstallStatusVisuals.ErrorColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, style = MaterialTheme.typography.bodyMedium, color = InstallStatusVisuals.ErrorColor)
            }
            Spacer(Modifier.height(8.dp))
            InstallRetryButton(onInstallOnCar, stringResource(R.string.car_app_retry))
        }
        else -> {
            // In-progress (searching/connecting/pushing/installing/launching) or idle.
            if (status.isInProgress) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
                ) {
                    InstallStageProgress(installStatus, stageIndex)
                }
                Spacer(Modifier.height(8.dp))
                Text(carSkipBtn, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                // Idle — offer the initial install button.
                Button(
                    onClick = onInstallOnCar,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(carInstallBtn, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (!status.isInProgress) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(carSkipBtn, color = Color.Gray)
        }
    }
}

@Composable
private fun InstallRetryButton(onInstallOnCar: () -> Unit, label: String) {
    Button(
        onClick = onInstallOnCar,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Stage checklist shown during install. Kept public because the in-card stage
 * progress visualization lives here (the legacy InstallStatusCard that shared
 * it has been removed); the onboarding step is the only current caller.
 */
@Composable
fun InstallStageProgress(status: String, stageIndex: Int = InstallStatus.stageIndex(status)) {
    Column(modifier = Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Medium)
        }
        if (stageIndex >= 0) {
            Spacer(Modifier.height(10.dp))
            InstallStatus.stageKeywords.forEachIndexed { index, (_, labelRes) ->
                val stageState = when {
                    index < stageIndex -> "done"
                    index == stageIndex -> "active"
                    else -> "pending"
                }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        when (stageState) {
                            "done" -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = InstallStatusVisuals.DoneColor, modifier = Modifier.size(12.dp))
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
                        stringResource(labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (stageState) {
                            "done" -> InstallStatusVisuals.DoneColor
                            "active" -> Color.White
                            // #757575 on the #1A2332 stage card measured 3.43:1 at
                            // 12sp; onSurfaceVariant measures 7.45:1 (audit UX-05).
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
