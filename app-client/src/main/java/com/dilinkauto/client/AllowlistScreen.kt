package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dilinkauto.client.service.AllowlistRepository
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.service.LauncherApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone-side picker for which apps the car is allowed to show. The selection lives
 * in SharedPreferences (`dilinkauto_allowlist` / `allowed_packages`) and is read by
 * [ConnectionService.sendAppList] to filter the wire payload before it reaches the car.
 *
 * Toggling a row writes the prefs and fires [ConnectionService.ACTION_ALLOWLIST_UPDATED]
 * so the running service re-sends the list immediately — the car grid updates live.
 */
@Composable
fun AllowlistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    data class AllowRow(val pkg: String, val label: String)

    // Launcher apps are stable for the session — load once, off the main thread.
    var apps by remember { mutableStateOf<List<AllowRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            LauncherApps.queryResolveInfos(pm)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .map { AllowRow(it.first, it.second) }
                .sortedBy { it.label.lowercase() }
        }
    }

    val allowlistRepo = remember { AllowlistRepository(context) }
    var allowed by remember { mutableStateOf(allowlistRepo.getAllowedPackages() ?: emptySet()) }
    var query by rememberSaveable { mutableStateOf("") }

    fun persist(next: Set<String>) {
        allowed = next
        allowlistRepo.saveAllowedPackages(next)
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }
            Text(stringResource(R.string.allowlist_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.allowlist_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.allowlist_search_hint), color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            // Without a name TalkBack announces only "button", leaving
                            // the purpose unreachable for screen-reader users (UX-04).
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_search),
                                tint = Color.Gray
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { persist(apps.map { it.pkg }.toSet()) },
                    shape = RoundedCornerShape(10.dp)
                ) { Text(stringResource(R.string.allowlist_select_all)) }
                OutlinedButton(
                    onClick = { persist(emptySet()) },
                    shape = RoundedCornerShape(10.dp)
                ) { Text(stringResource(R.string.allowlist_deselect_all)) }
            }

            Spacer(Modifier.height(8.dp))
            Text("${allowed.size} / ${apps.size}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (allowed.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.allowlist_empty), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFA726))
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (filtered.isEmpty() && query.isNotBlank()) {
                // Say why the list is empty and offer the way out (audit UX-09):
                // a blank list reads as "still loading".
                item(key = "no_match") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.allowlist_no_match, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { query = "" }) {
                            Text(stringResource(R.string.clear_search))
                        }
                    }
                }
            }
            items(filtered, key = { it.pkg }) { row ->
                val checked = row.pkg in allowed
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = remember(row.pkg) {
                            try { pm.getApplicationIcon(row.pkg) } catch (_: PackageManager.NameNotFoundException) { null }
                        }
                        if (icon != null) {
                            val bmp = remember(row.pkg) {
                                IconRenderer.toBitmap(icon, 28, rescale = false)
                            }
                            Icon(
                                painter = BitmapPainter(bmp.asImageBitmap()),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(Icons.Default.Apps, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.label, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                            Text(row.pkg, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = {
                                val next = if (it) allowed + row.pkg else allowed - row.pkg
                                persist(next)
                            }
                        )
                    }
                }
            }
        }
    }
}
