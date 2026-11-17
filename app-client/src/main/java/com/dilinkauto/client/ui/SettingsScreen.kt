package com.dilinkauto.client.ui

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.service.ConnectionService

/**
 * Settings screen: permissions, debug, about.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenBatteryExemption: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAllowlist: () -> Unit
) {
    val context = LocalContext.current
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
    val hasAllFiles = remember(permissionsKey) { PermissionChecker.hasAllFilesAccess() }
    val hasBattery = remember(permissionsKey) { PermissionChecker.hasBatteryExemption(context, pkg) }
    val hasAccessibility = remember(permissionsKey) { PermissionChecker.hasAccessibility(context, pkg) }
    // 初值必须反映「真的会不会落盘」，而不是「偏好里存了什么」（issue #72）：
    // release 下用户没设过开关时，旧写法显示「开」，运行时却按 BuildConfig.DEBUG
    // 关掉了日志，整段导航一行都没记。
    var logEnabled by remember {
        mutableStateOf(
            com.dilinkauto.client.FileLog.isEnabledIn(
                context.getSharedPreferences(com.dilinkauto.protocol.AppPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
            )
        )
    }

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
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Permissions
            Text(stringResource(R.string.settings_permissions), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            SetupItem(
                icon = if (hasAllFiles) Icons.Default.CheckCircle else Icons.Default.Folder,
                title = if (hasAllFiles) "${stringResource(R.string.perm_all_files)} ✓" else stringResource(R.string.perm_all_files),
                description = if (hasAllFiles) stringResource(R.string.perm_granted) else stringResource(R.string.perm_all_files_granted),
                onClick = onOpenAllFilesAccess
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = if (hasBattery) Icons.Default.CheckCircle else Icons.Default.BatterySaver,
                title = if (hasBattery) "${stringResource(R.string.perm_battery)} ✓" else stringResource(R.string.perm_battery),
                description = if (hasBattery) stringResource(R.string.perm_granted) else stringResource(R.string.perm_battery_granted),
                onClick = onOpenBatteryExemption
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = if (hasAccessibility) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                title = if (hasAccessibility) "${stringResource(R.string.perm_accessibility)} ✓" else stringResource(R.string.perm_accessibility),
                description = if (hasAccessibility) stringResource(R.string.perm_granted) else stringResource(R.string.perm_accessibility_granted),
                onClick = onOpenAccessibility
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = Icons.Default.Usb,
                title = stringResource(R.string.perm_usb_debugging),
                description = stringResource(R.string.perm_usb_desc),
                onClick = onOpenDeveloperOptions
            )

            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = Icons.Default.Apps,
                title = stringResource(R.string.allowlist_title),
                description = stringResource(R.string.allowlist_desc),
                onClick = onOpenAllowlist
            )

            Spacer(Modifier.height(8.dp))

            // Shizuku
            val shizukuInstalled = remember(permissionsKey) { ShizukuManager.isInstalled }
            val shizukuAvailable = remember(permissionsKey) { ShizukuManager.isAvailable }
            val shizukuIcon = when {
                shizukuAvailable -> Icons.Default.Shield
                shizukuInstalled -> Icons.Default.Security
                else -> Icons.Default.Info
            }
            val shizukuTitle = when {
                shizukuAvailable -> stringResource(R.string.perm_shizuku_available)
                shizukuInstalled -> stringResource(R.string.perm_shizuku_needs_permission)
                else -> stringResource(R.string.perm_shizuku)
            }
            val shizukuDesc = when {
                shizukuAvailable -> stringResource(R.string.perm_shizuku_granted)
                shizukuInstalled -> stringResource(R.string.perm_shizuku_permission_desc)
                else -> stringResource(R.string.perm_shizuku_desc)
            }
            SetupItem(
                icon = shizukuIcon,
                title = shizukuTitle,
                description = shizukuDesc,
                // Every branch must do something: the row is drawn with a chevron
                // and a ripple, so a branch that falls through is a control that
                // lies about being interactive (audit UX-13).
                onClick = {
                    when {
                        shizukuAvailable -> ShizukuNav.openShizukuApp(context)
                        shizukuInstalled -> {
                            ShizukuManager.requestPermission()
                            ShizukuNav.openShizukuApp(context)
                            permissionsKey++
                        }
                        else -> ShizukuNav.openShizukuStorePage(context)
                    }
                }
            )

            Spacer(Modifier.height(32.dp))

            // Debug
            Text(stringResource(R.string.settings_debug), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.log_enabled), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.log_enabled_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Switch(
                        checked = logEnabled,
                        onCheckedChange = { enabled ->
                            logEnabled = enabled
                            ConnectionService.setLogEnabled(context, enabled)
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // About
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val versionName = com.dilinkauto.client.service.AppVersion.nameOrEmpty(context)
                        .ifEmpty { stringResource(R.string.value_unknown) }
                    Text(stringResource(R.string.about_version, versionName), fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_libs_heading), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color(0xFFB0BEC5))
                    Text(stringResource(R.string.about_lib_dadb), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_compose), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_coroutines), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_scrcpy), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(32.dp))
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
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}
