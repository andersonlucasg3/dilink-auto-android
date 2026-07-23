package com.dilinkauto.client.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.dilinkauto.client.DiLinkAutoTheme
import com.dilinkauto.client.auto.AaDaemonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home screen of the AA virtual display (seed of "Fase 3"). The daemon starts
 * it by explicit component on the VD — the stock launcher is singleTask on
 * display 0, so a generic HOME intent would background the host app.
 *
 * Only ever runs on the virtual display: no top bar, no navigation chrome.
 */
class DiLinkLauncher : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiLinkAutoTheme {
                LauncherGrid()
            }
        }
    }
}

private data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

@Composable
private fun LauncherGrid() {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<LaunchableApp>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map {
                    LaunchableApp(
                        packageName = it.activityInfo.packageName,
                        label = it.loadLabel(pm).toString(),
                        icon = it.loadIcon(pm).toBitmap().asImageBitmap()
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(apps) { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    try { AaDaemonClient.daemon?.launchApp(app.packageName) } catch (_: Exception) {}
                }
            ) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label,
                    fontSize = 12.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
