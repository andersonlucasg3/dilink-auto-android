package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.R

@Composable
fun NavActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // The icon is 40dp, so without a floor the touch target in the rail
            // was only 40dp wide — under the 44–48dp guidance, on a control that
            // is tapped blind while driving (audit UX-11). The rail has 68dp of
            // usable width, so a 48dp floor fits without clipping.
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            // 禁用态（issue #152-PA 的「最近」键在重握手窗口内）不响应点击，
            // 避免把 GO_RECENT 发进正在拆除的连接、刷 `dropped` 日志。
            .clickable(enabled = enabled, onClick = onClick)
            // issue #152-PA：竖向 padding 10dp -> 6dp，给第 4 个键腾出高度；
            // 触控高度仍有 40 + 12 = 52dp，高于 48dp 无障碍下限。
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(40.dp)
        )
    }
}
