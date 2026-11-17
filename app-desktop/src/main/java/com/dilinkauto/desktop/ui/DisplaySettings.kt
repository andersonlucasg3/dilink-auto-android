package com.dilinkauto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.desktop.display.DesktopScreen
import com.dilinkauto.protocol.VdDeployArgs

/**
 * 显示面板要展示的全部状态。
 *
 * 收成一个数据类是为了让 [DesktopWindow] 的调用点保持可读——否则这个面板要
 * 传十来个零散参数（Phase 5a 的全屏/多显示器 + DPI 覆盖，5b 的硬解，5d 的常亮）。
 */
data class DisplayPanelState(
    val screens: List<DesktopScreen>,
    val fullscreen: Boolean,
    /** 当前配置的 DPI 覆盖；0 = 手机自动标定。 */
    val dpi: Int,
    /** 当前配置是否启用硬解。 */
    val hwaccel: Boolean,
    /** 解码器实际在用硬解吗（回退软解后为 false）。 */
    val hardwareInUse: Boolean,
    val keepAwake: Boolean,
    val keepAwakeSupported: Boolean,
    /** 手机**物理**屏的期望状态（连上后 vd-server 默认已熄屏，这里是"让它亮/灭"的意图）。 */
    val phoneScreenOn: Boolean,
    /** 命令没进入输入通道时的提示（audit WIN-12）；null = 无需提示。 */
    val phoneScreenHint: String? = null,
    /** 是否有活动会话可以下达屏幕电源命令（无会话时禁用开关）。 */
    val phoneScreenEnabled: Boolean,
)

/**
 * 显示设置面板（左侧导航的第三页）。
 *
 * 三条约束决定了这里的交互：
 *  - **DPI / 硬解改了必须重连**：握手参数只在建连时发一次，所以是"应用并重连"按钮，
 *    不是即时生效的开关；
 *  - **全屏 / 换显示器是本机窗口行为**：不需要重连，点一下即可；
 *  - **常亮开关可以随时切**：由 `KeepAwake` 直接生效，与连接无关；
 *  - **手机物理屏开关走协议**：发到输入口的 CONTROL 通道，由 vd-server 执行（Phase 5e）。
 */
@Composable
internal fun DisplaySettingsView(
    state: DisplayPanelState,
    onToggleFullscreen: (Boolean) -> Unit,
    onMoveToScreen: (DesktopScreen) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onTogglePhoneScreen: (Boolean) -> Unit,
    onApplyAndReconnect: (dpi: Int, hwaccel: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 面板自己的编辑态，初值来自当前配置；"应用并重连"后与配置同步。
    var dpiText by remember(state.dpi) { mutableStateOf(state.dpi.toString()) }
    var hwaccel by remember(state.hwaccel) { mutableStateOf(state.hwaccel) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Backdrop)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("窗口")
        CheckRow(
            label = "全屏（F11）",
            checked = state.fullscreen,
            onCheckedChange = onToggleFullscreen,
        )
        if (state.screens.size > 1) {
            Body("显示器")
            state.screens.forEach { screen ->
                ActionButton(
                    label = screen.label,
                    onClick = { onMoveToScreen(screen) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        SectionTitle("画质（改动需重连）")
        Body("DPI 覆盖（0 = 手机自动标定；有效范围 ${VdDeployArgs.DPI_OVERRIDE_MIN}–${VdDeployArgs.DPI_OVERRIDE_MAX}，越界会被夹紧）")
        BasicTextField(
            value = dpiText,
            onValueChange = { input -> dpiText = input.filter(Char::isDigit).take(3) },
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 13.sp),
            cursorBrush = SolidColor(Palette.Text),
            modifier = Modifier
                .width(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Button)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        CheckRow(
            label = "D3D11VA 硬解（不支持时自动回退软解）",
            checked = hwaccel,
            onCheckedChange = { hwaccel = it },
        )
        ActionButton(
            label = "应用并重连",
            onClick = {
                onApplyAndReconnect(dpiText.toIntOrNull() ?: 0, hwaccel)
            },
        )
        Body(
            if (state.hwaccel) {
                if (state.hardwareInUse) "当前解码：硬解" else "当前解码：硬解已回退为软解"
            } else {
                "当前解码：软解"
            },
        )

        Spacer(Modifier.height(6.dp))
        SectionTitle("本机")
        CheckRow(
            label = "会话期间保持本机屏幕常亮",
            checked = state.keepAwake,
            enabled = state.keepAwakeSupported,
            onCheckedChange = onToggleKeepAwake,
        )
        if (!state.keepAwakeSupported) {
            Body("本机不支持（仅 Windows 可用；需 JNA 能加载 kernel32）")
        }

        Spacer(Modifier.height(6.dp))
        SectionTitle("手机")
        CheckRow(
            label = "点亮手机物理屏",
            checked = state.phoneScreenOn,
            enabled = state.phoneScreenEnabled,
            onCheckedChange = onTogglePhoneScreen,
        )
        Body("连上后手机会自动熄屏；此开关用于中途手动点亮/熄灭（走输入口命令）。")
        state.phoneScreenHint?.let { Body(it) }
    }
}
