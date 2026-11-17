package com.dilinkauto.server.service

import android.content.Context
import android.os.Build
import com.dilinkauto.protocol.HandshakeRequest
import com.dilinkauto.protocol.VdAreaTier

/**
 * Builds the car's [HandshakeRequest] to the phone.
 *
 * The same fields are populated at two call sites — initial connect and
 * mid-stream rotation re-handshake — so the package-version lookup and
 * device-name formatting live here once. Only the viewport dims and DPI differ
 * between the two sites; callers compute those from the current display state.
 *
 * Extracted from [CarConnectionService] to remove duplication between
 * `connectToPhone` and `onCarViewportChanged`.
 */
internal fun buildHandshakeRequest(
    context: Context,
    screenWidth: Int,
    screenHeight: Int,
    screenDpi: Int,
    targetFps: Int,
    dpiOverride: Int,
    bitrate: Int = 0,
    audioEnabled: Boolean,
    /** issue #153 PB：会话级 VD 面积档位，手机建 VD 时取用；老手机忽略该尾字段。 */
    vdAreaTier: Int = VdAreaTier.AUTO
): HandshakeRequest {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    // Dimensions arrive pre-aligned from the caller (CarViewport.size), because
    // on this side the nav bar makes the correct direction "widen the bar" —
    // the opposite of the desktop's floor. Aligning again here would undo it.
    return HandshakeRequest.builder()
        .deviceName("DiLink-${Build.MODEL}")
        .screenSize(screenWidth, screenHeight)
        .screenDpi(screenDpi)
        .appVersionCode(@Suppress("DEPRECATION") pi.versionCode)
        .targetFps(targetFps)
        .appVersionName(pi.versionName ?: "")
        .dpiOverride(dpiOverride)
        .bitrate(bitrate)
        .vdAreaTier(vdAreaTier)
        // 按用户开关声明音频位（issue #72）：只有确实打开了「音频镜像」的车机才
        // advertise FEATURE_AUDIO，避免向手机假广告一个用户并不想要的 9640 通道。
        // 位拼装在 [carRequestFeatures]，纯函数、已单测。
        .supportedFeatures(carRequestFeatures(audioEnabled))
        .build()
}
