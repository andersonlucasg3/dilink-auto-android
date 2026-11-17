package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.AppInfoDataMessage
import com.dilinkauto.server.R

/**
 * App-info dialog (audit R3-SRP-19): package / version / install-time details
 * pushed from the phone via `AppInfoDataMessage`.
 *
 * Extracted from [CarShell], which keeps only screen routing; the date
 * formatting lives next to its only consumer.
 */
@Composable
internal fun AppInfoDialog(info: AppInfoDataMessage, onDismiss: () -> Unit) {
    val dateStr = remember(info.installTime) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(info.installTime))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.appName, color = Color.White, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.app_info_package), info.packageName)
                InfoRow(stringResource(R.string.app_info_version), info.versionName)
                InfoRow(stringResource(R.string.app_info_version_code), info.versionCode.toString())
                InfoRow(stringResource(R.string.app_info_target_sdk), info.targetSdk.toString())
                InfoRow(stringResource(R.string.app_info_installed), dateStr)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = Color.White,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "$label:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
