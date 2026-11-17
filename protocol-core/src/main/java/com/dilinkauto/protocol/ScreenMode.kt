package com.dilinkauto.protocol

/**
 * 手机投屏采集源模式（接收端"模式"功能的采集侧降级阶梯）。
 *
 * 背景（VD 模式可用，但部分手机品牌无法点亮 VirtualDisplay 画面）：采集优先用
 * [VIRTUAL_DISPLAY]（vd-server 模式，IndependentVirtualDisplay + 编码，对前台 app 干扰最小）；
 * 当该设备无法点亮 VD 画面时，按 [CaptureModePolicy] 自动降级到投影采集：
 *  - [PROJECTION_SCREEN_OFF] 息屏投屏（MediaProjection，可熄屏，最省电、最接近"车机"体验）
 *  - [PROJECTION_MAIN]       主屏投屏（MediaProjection 主显示，兜底：部分 ROM 息屏投影受限）
 *
 * 平台无关， phone 端(vd-server/采集) 与车机/桌面接收端共用同一份判定，避免两端策略漂移。
 */
enum class ScreenMode {
    /** vd-server 模式：shell-UID VirtualDisplay 采集 + 编码。 */
    VIRTUAL_DISPLAY,

    /** 息屏投屏：MediaProjection + 可熄屏。VD 无法点亮时的首选降级。 */
    PROJECTION_SCREEN_OFF,

    /** 主屏投屏：MediaProjection 主显示。最后兜底。 */
    PROJECTION_MAIN,
}

/**
 * 采集模式的选择与自动降级策略（纯函数，无平台依赖，可单测）。
 *
 * 约定：**VD 优先**；仅在"该设备无法点亮 VD"或运行时 VD 创建/首帧失败时逐级降级，
 * 不允许跳过中间级直接掉到主屏，也不允许从主屏再降（[PROJECTION_MAIN] 是最后手段）。
 */
object CaptureModePolicy {

    /** 首选采集模式：设备支持点亮 VD 用 [ScreenMode.VIRTUAL_DISPLAY]，否则直接息屏投屏。 */
    fun preferred(vdLightingSupported: Boolean): ScreenMode =
        if (vdLightingSupported) ScreenMode.VIRTUAL_DISPLAY else ScreenMode.PROJECTION_SCREEN_OFF

    /**
     * 运行时 [current] 采集失败后的下一级降级；返回 null 表示已无更低级（[ScreenMode.PROJECTION_MAIN]）。
     * 阶梯：VIRTUAL_DISPLAY → PROJECTION_SCREEN_OFF → PROJECTION_MAIN → (null)。
     */
    fun fallbackFrom(current: ScreenMode): ScreenMode? = when (current) {
        ScreenMode.VIRTUAL_DISPLAY -> ScreenMode.PROJECTION_SCREEN_OFF
        ScreenMode.PROJECTION_SCREEN_OFF -> ScreenMode.PROJECTION_MAIN
        ScreenMode.PROJECTION_MAIN -> null
    }

    /** 完整的降级链（含首级），便于 UI 展示"将依次尝试：VD / 息屏 / 主屏"。 */
    fun ladder(start: ScreenMode): List<ScreenMode> {
        val out = mutableListOf(start)
        var next = fallbackFrom(start)
        while (next != null) { out.add(next); next = fallbackFrom(next) }
        return out
    }
}
