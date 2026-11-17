package com.dilinkauto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.desktop.SessionState
import com.dilinkauto.desktop.apps.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 桌面端共享的小组件与空会话占位流（audit R3-SRP-14/15）。
 *
 * 四个无 material 依赖的原语此前锁在 `DisplaySettings.kt` 里且为 private，
 * 别的视图要用只能再写一份；占位流此前是 `DesktopWindow.kt` 的私有实现。
 * 二者都属于"桌面 UI 的公共面"，集中到这里后各视图直接复用。
 */

/** 小节标题。 */
@Composable
internal fun SectionTitle(text: String) {
    BasicText(text, style = TextStyle(color = Palette.Text, fontSize = 14.sp))
}

/** 正文说明。 */
@Composable
internal fun Body(text: String) {
    BasicText(text, style = TextStyle(color = Palette.TextDim, fontSize = 12.sp))
}

/** 无 material 依赖的勾选行：左侧一个方块 + 标签。 */
@Composable
internal fun CheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val color = if (enabled) Palette.Text else Palette.TextDim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) Palette.Accent else Palette.Button),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) BasicText("✓", style = TextStyle(color = Palette.Backdrop, fontSize = 11.sp))
        }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = TextStyle(color = color, fontSize = 13.sp))
    }
}

/** 无 material 依赖的按钮。[primary] = 强调色底 + 深色文字（如「重试连接」）。 */
@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) Palette.Accent else Palette.Button)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(color = if (primary) Palette.Backdrop else Palette.Text, fontSize = 13.sp),
        )
    }
}

/** 空会话时给 `collectAsState` 用的占位流（避免条件式调用 composable）。 */
internal val NoSessionEnded = MutableStateFlow(false)
internal val NoHardwareDecode = MutableStateFlow(false)
internal val NoApps = MutableStateFlow(emptyList<AppEntry>())
internal val NoLastError = MutableStateFlow<String?>(null)
internal val NoState = MutableStateFlow(SessionState.IDLE)
