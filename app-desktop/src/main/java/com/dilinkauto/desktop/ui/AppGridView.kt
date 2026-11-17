package com.dilinkauto.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.desktop.apps.AppEntry

/**
 * 应用启动器网格：点图标 → 通过输入口发 `LAUNCH_APP` 让手机启动该应用。
 *
 * 图标是手机下发的 PNG，解码后是 AWT [java.awt.image.BufferedImage]，
 * 这里用 `toComposeImageBitmap()` 转成 Compose 位图。转换按 [AppEntry.icon]
 * 的实例缓存（`remember`）——手机抑制重复图标时图标实例不变，不会反复转换。
 */
@Composable
fun AppGridView(
    apps: List<AppEntry>,
    onLaunch: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(
                "手机上还没有可启动的应用",
                style = TextStyle(color = Palette.TextDim, fontSize = 14.sp),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppTile(app = app, onClick = { onLaunch(app.packageName) })
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Button)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(app)
        BasicText(
            app.appName,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = Palette.Text,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图标本体；手机没给图标时退化成首字母占位块。 */
@Composable
private fun AppIcon(app: AppEntry) {
    val bitmap = remember(app.icon) { app.icon?.toComposeImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = app.appName,
            modifier = Modifier.size(ICON_SIZE),
        )
    } else {
        Box(
            modifier = Modifier.size(ICON_SIZE).clip(RoundedCornerShape(12.dp)).background(Palette.Rail),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                app.appName.take(1),
                style = TextStyle(color = Palette.Text, fontSize = 20.sp),
            )
        }
    }
}

private val ICON_SIZE = 48.dp
