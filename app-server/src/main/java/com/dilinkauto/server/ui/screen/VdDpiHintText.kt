package com.dilinkauto.server.ui.screen

import com.dilinkauto.protocol.VdDeployArgs

/**
 * 设置页 DPI 输入框下方的"实际生效值 / 视口上限"提示（issue #202 评审第 2 条）。
 *
 * 为什么需要它：越界 override 会回落到自动标定值（phone 侧 `VdDpiResolver`），但设置页
 * 仍显示用户填的 480 —— 用户会以为"填了没生效"，或反过来以为 480 正在生效。
 *
 * 数据全部来自**现有**握手响应（`HandshakeResponse.vdDpi` + displayWidth/Height），
 * 不新增协议字段；还没握手成功时（[effectiveDpi] / [safeMaxDpi] 为 0）退化为静态提示。
 *
 * 纯函数：文案是评审要求可钉住的产物，放这里才能脱机断言。
 */
internal object VdDpiHintText {

    /** 没握手过时的静态提示。 */
    const val STATIC = "超过 120..480 或本视口上限的输入会按自动标定值生效"

    /**
     * @param startupDpi 设置页里用户填的 override（0 = Auto）。
     * @param effectiveDpi 上次握手手机端回传的实际生效 DPI（0 = 还没握手）。
     * @param safeMaxDpi 该 VD 视口的密度上限（0 = 还没握手）。
     */
    fun text(startupDpi: Int, effectiveDpi: Int, safeMaxDpi: Int): String {
        if (effectiveDpi <= 0 || safeMaxDpi <= 0) return STATIC
        val effective = "上次实际生效 ${effectiveDpi}dpi"
        val cap = "当前视口上限 ${safeMaxDpi}dpi"
        return when {
            // 用户什么都没设：只说事实，不要暗示"你的设置被改了"。
            startupDpi <= 0 -> "$effective；$cap"
            startupDpi > safeMaxDpi ->
                "$effective；$cap，设置 ${startupDpi}dpi 超出，将按自动标定值使用"
            startupDpi !in VdDeployArgs.DPI_OVERRIDE_MIN..VdDeployArgs.DPI_OVERRIDE_MAX ->
                "$effective；$cap，设置 ${startupDpi}dpi 非法，将按自动标定值使用"
            else -> "$effective；$cap，设置 ${startupDpi}dpi 会原样生效"
        }
    }
}
