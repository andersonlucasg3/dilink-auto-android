package com.dilinkauto.client.launcher.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.R
import com.dilinkauto.client.launcher.LauncherApp
import com.dilinkauto.client.launcher.launchAppInfo
import com.dilinkauto.client.launcher.launchUninstall
import com.dilinkauto.client.launcher.nav.rememberAppIcon
import com.dilinkauto.client.launcher.theme.*
import com.dilinkauto.protocol.AppCategory
import kotlin.math.max

/**
 * Car-optimized app grid — fixed column count, search bar at the bottom.
 */
@Composable
fun AppGrid(
    apps: List<LauncherApp>,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.weight(1f)) {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                // Fixed columns calculated from available width — same density as
                // Adaptive(100.dp) but without the runtime measurement crash risk
                val gridColumns = max(3, (maxWidth / 100.dp).toInt().coerceAtMost(12))

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }, contentType = { "app_tile" }) { app ->
                        AppTile(
                            app = app,
                            onClick = { onAppClick(app.packageName) }
                        )
                    }
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { newValue ->
                searchQuery = newValue
            },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = Color.Gray
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color(0xFF2A2F3A),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Text(
            text = stringResource(R.string.landscape_app_note),
            fontSize = 11.sp,
            color = Color(0xFF666666),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: LauncherApp,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    val categoryIcon = when (app.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        AppCategory.OTHER -> Icons.Default.Apps
    }

    val categoryColor = when (app.category) {
        AppCategory.NAVIGATION -> NavigationColor
        AppCategory.MUSIC -> MusicColor
        AppCategory.COMMUNICATION -> CommunicationColor
        AppCategory.OTHER -> OtherColor
    }

    val iconBitmap = rememberAppIcon(app.packageName)

    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(64.dp)
                )
            } else {
                Icon(
                    categoryIcon,
                    contentDescription = null,
                    tint = categoryColor,
                    modifier = Modifier.size(64.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(8.dp, 0.dp),
            modifier = Modifier
                .widthIn(min = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_uninstall), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    launchUninstall(context, app.packageName)
                },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350), modifier = Modifier.size(28.dp))
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_app_info), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    launchAppInfo(context, app.packageName)
                },
                leadingIcon = {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF64B5F6), modifier = Modifier.size(28.dp))
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}
