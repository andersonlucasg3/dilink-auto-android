package com.dilinkauto.server.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.server.R

/** DPI 提示的四种可见状态（issue #202 终审第 2 条：文案走 i18n）。 */
internal enum class DpiHintState {
    /** 还没握手过：没有生效值/视口上限可用。 */
    NO_HANDSHAKE,

    /** 用户设为 Auto。 */
    AUTO,

    /** 用户设置在本视口上限以内，会原样生效。 */
    IN_RANGE,

    /** 用户设置超过本视口上限，会回落自动标定值。 */
    OVER_CAP,

    /** 用户设置不在协议区间 120..480，会回落自动标定值。 */
    INVALID
}

/**
 * 设置页 DPI 提示的**纯数据**结论（issue #202 评审第 2 条 / 终审第 2 条）。
 *
 * 为什么需要它：越界 override 会回落到自动标定值（phone 侧 `VdDpiResolver`），但设置页
 * 仍显示用户填的 480 —— 用户会以为"填了没生效"，或反过来以为 480 正在生效。
 *
 * 数据全部来自**现有**握手响应（`HandshakeResponse.vdDpi` + displayWidth/Height），
 * 不新增协议字段。这里只判断状态与参数，**不产出任何文案**：文案由 [dpiHintText]
 * 用 `stringResource` 组装，保证英文环境不会掉出中文。
 */
internal data class DpiHint(
    val state: DpiHintState,
    val effectiveDpi: Int = 0,
    val safeMaxDpi: Int = 0,
    val startupDpi: Int = 0
) {
    companion object {
        /**
         * @param startupDpi 设置页里用户填的 override（0 = Auto）。
         * @param effectiveDpi 上次握手手机端回传的实际生效 DPI（0 = 还没握手）。
         * @param safeMaxDpi 该 VD 视口的密度上限（0 = 还没握手）。
         */
        fun of(startupDpi: Int, effectiveDpi: Int, safeMaxDpi: Int): DpiHint {
            if (effectiveDpi <= 0 || safeMaxDpi <= 0) return DpiHint(DpiHintState.NO_HANDSHAKE)
            return when {
                startupDpi <= 0 -> DpiHint(DpiHintState.AUTO, effectiveDpi, safeMaxDpi)
                startupDpi > safeMaxDpi -> DpiHint(DpiHintState.OVER_CAP, effectiveDpi, safeMaxDpi, startupDpi)
                startupDpi !in VdDeployArgs.DPI_OVERRIDE_MIN..VdDeployArgs.DPI_OVERRIDE_MAX ->
                    DpiHint(DpiHintState.INVALID, effectiveDpi, safeMaxDpi, startupDpi)
                else -> DpiHint(DpiHintState.IN_RANGE, effectiveDpi, safeMaxDpi, startupDpi)
            }
        }
    }
}

/**
 * 把 [DpiHint] 渲染成设置页文案（Compose 侧）。
 *
 * 终审第 3 条：视口/生效值都来自**上一次会话**的握手，用户在本次会话改档位后这些数不会
 * 立刻更新，所以每条带数字的文案都追加「以上次会话视口为准」，避免被当成实时值。
 */
@Composable
internal fun dpiHintText(hint: DpiHint): String {
    if (hint.state == DpiHintState.NO_HANDSHAKE) return stringResource(R.string.dpi_hint_static)
    val basis = stringResource(R.string.dpi_hint_basis)
    return when (hint.state) {
        DpiHintState.AUTO -> stringResource(
            R.string.dpi_hint_auto, hint.effectiveDpi, hint.safeMaxDpi, basis
        )
        DpiHintState.IN_RANGE -> stringResource(
            R.string.dpi_hint_in_range, hint.effectiveDpi, hint.safeMaxDpi, hint.startupDpi, basis
        )
        DpiHintState.OVER_CAP -> stringResource(
            R.string.dpi_hint_over_cap, hint.effectiveDpi, hint.safeMaxDpi, hint.startupDpi, basis
        )
        DpiHintState.INVALID -> stringResource(
            R.string.dpi_hint_invalid, hint.effectiveDpi, hint.safeMaxDpi, hint.startupDpi, basis
        )
        DpiHintState.NO_HANDSHAKE -> stringResource(R.string.dpi_hint_static)
    }
}
