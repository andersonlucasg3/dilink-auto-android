package com.dilinkauto.desktop

import com.dilinkauto.protocol.DimAlign
import com.dilinkauto.protocol.HandshakeRequest

/**
 * 由桌面配置构造发给手机的 [HandshakeRequest]。
 *
 * 车机端对应的是 `app-server` 的 `buildHandshakeRequest`；桌面端没有 PackageManager / Build.MODEL，
 * 设备名与版本号改由配置提供。视口尺寸做偶数对齐（H.264 编码器要求偶数边长）。
 */
object HandshakeFactory {

    fun build(config: DesktopConfig): HandshakeRequest = HandshakeRequest.builder()
        .deviceName(config.deviceName)
        // The desktop is the sender, so it owns the alignment here. The car side
        // deliberately does NOT align inside its factory — it relies on its caller
        // (getViewportSize), where the nav bar makes the correct direction
        // "widen the bar", the opposite of this floor.
        .screenSize(evenAlign(config.viewportWidth), evenAlign(config.viewportHeight))
        .screenDpi(config.screenDpi)
        .appVersionCode(config.appVersionCode)
        .targetFps(config.targetFps)
        .dpiOverride(config.dpiOverride)
        .bitrate(config.bitrate)
        .vdAreaTier(config.vdAreaTier)
        .build()

    /** 向下取偶数，并保证最小为 2（奇数边长会被 H.264 编码器拒绝或导致画面错位）。 */
    fun evenAlign(value: Int): Int = DimAlign.evenMin2(value)
}