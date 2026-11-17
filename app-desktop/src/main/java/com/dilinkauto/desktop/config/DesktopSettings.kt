package com.dilinkauto.desktop.config

import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VideoConfig

/**
 * 桌面端持久化配置（`%APPDATA%\DiLinkAuto\config.json`）。
 *
 * 键名刻意沿用 Android 侧的 `SharedPreferences` 键（见车机 `CarConnectionService`
 * 与 `AppPrefs`），这样两端可以对照排查，也方便未来做配置同步。
 *
 * | 键 | 含义 | 桌面端用途 |
 * |---|---|---|
 * | `dev_phone_ip` | 手机 IP | 命令行没传 IP 时的默认值 |
 * | `dev_mode` | 开发者模式 | Phase 5c 的 ADB 部署路径开关 |
 * | `startup_dpi` | 强制 DPI（0=自动，或 120..480） | 握手时的 `dpiOverride` |
 * | `startup_fps` | 目标帧率 | 握手时的 `targetFps` |
 * | `startup_bitrate` | 目标码率 | 握手时的 `bitrate` |
 * | `startup_hwaccel` | 是否用 D3D11VA 硬解 | 解码管线构造参数（Phase 5b） |
 * | `keep_awake` | 会话期间是否阻止本机息屏 | `KeepAwake` 的默认开关（Phase 5d） |
 * | `log_enabled` | 是否写本地日志 | 控制 `desktop.log` |
 */
data class DesktopSettings(
    val devPhoneIp: String = "",
    val devMode: Boolean = false,
    val startupDpi: Int = 0,
    val startupFps: Int = VideoConfig.TARGET_FPS,
    val startupBitrate: Int = VideoConfig.DEFAULT_BITRATE,
    val logEnabled: Boolean = true,
    val startupHwaccel: Boolean = false,
    val keepAwake: Boolean = true,
) {

    fun toMap(): Map<String, Any?> = linkedMapOf(
        KEY_DEV_PHONE_IP to devPhoneIp,
        KEY_DEV_MODE to devMode,
        KEY_STARTUP_DPI to startupDpi,
        KEY_STARTUP_FPS to startupFps,
        KEY_STARTUP_BITRATE to startupBitrate,
        KEY_LOG_ENABLED to logEnabled,
        KEY_STARTUP_HWACCEL to startupHwaccel,
        KEY_KEEP_AWAKE to keepAwake,
    )

    fun toJson(): String = JsonConfig.write(toMap())

    // 读盘/写盘在 [DesktopSettingsStore]（audit R3-SRP-16）。

    companion object {
        // 跨端键名统一由 protocol-core 的 AppPrefs 定义（与手机/车机同源），
        // 这里保留 KEY_* 别名以免改动大量既有调用点。
        const val KEY_DEV_PHONE_IP = AppPrefs.DEV_PHONE_IP
        const val KEY_DEV_MODE = AppPrefs.DEV_MODE
        const val KEY_STARTUP_DPI = AppPrefs.STARTUP_DPI
        const val KEY_STARTUP_FPS = AppPrefs.STARTUP_FPS
        const val KEY_STARTUP_BITRATE = AppPrefs.STARTUP_BITRATE
        const val KEY_LOG_ENABLED = AppPrefs.LOG_ENABLED
        const val KEY_STARTUP_HWACCEL = "startup_hwaccel"
        const val KEY_KEEP_AWAKE = "keep_awake"

        /**
         * DPI 覆盖的合法区间**单点定义在 protocol-core**（[VdDeployArgs]）：0 = 手机
         * 自动标定，正数按 120..480 夹紧。桌面端此前自己写了一份 0..640，与协议不一致
         * —— 同一个值在 Shizuku 路径落到 480、在 ADB 路径原样发 640（audit WIN-08）。
         */
        private val FPS_RANGE = 5..60
        private val BITRATE_RANGE = 500_000..50_000_000

        /**
         * 从解析好的键值表构造配置。
         *
         * 类型不符的键一律退回默认值——手写的 config.json 很容易把 `"true"` 写成 `true`，
         * 与其崩在启动路径上，不如忽略这一项继续跑。
         */
        fun from(values: Map<String, Any?>): DesktopSettings {
            val defaults = DesktopSettings()
            return DesktopSettings(
                devPhoneIp = (values[KEY_DEV_PHONE_IP] as? String)?.trim().orEmpty(),
                devMode = (values[KEY_DEV_MODE] as? Boolean) ?: defaults.devMode,
                startupDpi = VdDeployArgs.coerceDpiOverride(
                    intValue(values[KEY_STARTUP_DPI], defaults.startupDpi),
                ),
                startupFps = intValue(values[KEY_STARTUP_FPS], defaults.startupFps).coerceIn(FPS_RANGE),
                startupBitrate = intValue(values[KEY_STARTUP_BITRATE], defaults.startupBitrate)
                    .coerceIn(BITRATE_RANGE),
                logEnabled = (values[KEY_LOG_ENABLED] as? Boolean) ?: defaults.logEnabled,
                startupHwaccel = (values[KEY_STARTUP_HWACCEL] as? Boolean) ?: defaults.startupHwaccel,
                keepAwake = (values[KEY_KEEP_AWAKE] as? Boolean) ?: defaults.keepAwake,
            )
        }

        private fun intValue(raw: Any?, fallback: Int): Int = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }
}
