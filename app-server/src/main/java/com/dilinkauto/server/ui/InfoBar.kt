package com.dilinkauto.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.ui.theme.NavBarBackgroundColor

/**
 * 顶部信息条（issue #39 落地脚手架）。
 *
 * 一条右对齐的横向信息容器，接 `List<InfoChip>`：#39 只放系统时间 chip，
 * #40（海拔）、#41（天气）后续直接往同一个 [InfoBar] 加 chip，不在各自 issue 里再造容器，
 * 避免三份重复布局（DRY）。
 *
 * - `chips` 为空时不渲染（也不占位）。镜像页不再传空：横屏走 [InfoChipColumn] 常驻左栏（issue #54）。
 * - 非交互：不抢焦点、不响应点击（issue #39 验收标准「非交互」）。
 * - 背景与导航条同色 [NavBarBackgroundColor]，视觉上是顶部 chrome 的一部分。
 *
 * 落位见 `CarShell` / `CarLaunchScreen`：竖屏顶部；横屏自 issue #54 起移入左侧常驻栏
 *（[InfoChipColumn]），不再占内容区高度，镜像页也常驻。
 */
@Immutable
data class InfoChip(val text: String)

/**
 * 左栏竖排信息 chip（issue #54）。
 *
 * 用户要求时间 / 海拔 / 天气固定在左侧那一栏、与三个导航键同栏，进入镜像页也不能消失。
 * 栏宽只有 76dp（`CarViewport.navBarWidthPx` 与编码视口偶数对齐强相关，不能改宽），
 * 因此这里用 12sp 等宽数字、单行省略，避免文本撑破栏宽反过来触发视口重握手。
 */
@Composable
fun InfoChipColumn(chips: List<InfoChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        chips.forEach { chip ->
            Text(
                text = chip.text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontFeatureSettings = "tnum"
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun InfoBar(chips: List<InfoChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NavBarBackgroundColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEachIndexed { index, chip ->
            if (index > 0) Spacer(Modifier.width(16.dp))
            Text(
                text = chip.text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End
            )
        }
    }
}
