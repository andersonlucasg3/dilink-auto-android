package com.dilinkauto.server.service

import android.content.Context
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.VideoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences facade for [CarConnectionService] (audit R3-SRP-01 item 9).
 *
 * Owns every pref key the car service touches, so the service no longer carries
 * hand-written `prefs.getX(...)` / `prefs.edit()...apply()` accessors — and the
 * future Wi-Fi/ADB/handshake split can take this facade instead of the whole
 * service.
 *
 * The four user-facing startup settings additionally expose an observable mirror
 * ([devModeFlow] etc.) so the UI binds to one source of truth instead of keeping
 * shadow state (R3-SRP-06). The property setters are the only write paths: they
 * update prefs and the flow together, so the rendered value cannot drift from the
 * stored one. The mirrors are initialized from the persisted values at
 * construction — there is no sync window.
 *
 * Instantiate lazily from the service (`by lazy`): a Service's field initializers
 * run before `attachBaseContext`, and opening the prefs file needs the base context.
 */
internal class CarPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)

    /** Set when the user explicitly disconnected; suppresses auto-reconnect until re-plug / START. */
    var userDisconnected: Boolean
        get() = prefs.getBoolean(AppPrefs.USER_DISCONNECTED, false)
        set(value) = prefs.edit().putBoolean(AppPrefs.USER_DISCONNECTED, value).apply()

    /** Manual phone IP; honored by the WiFi track only while [devMode] is on. */
    val devPhoneIp: String?
        get() = prefs.getString(AppPrefs.DEV_PHONE_IP, null)

    // ─── Dev mode ───

    private val _devMode = MutableStateFlow(prefs.getBoolean(AppPrefs.DEV_MODE, false))
    val devModeFlow: StateFlow<Boolean> = _devMode.asStateFlow()

    var devMode: Boolean
        get() = prefs.getBoolean(AppPrefs.DEV_MODE, false)
        set(value) {
            prefs.edit().putBoolean(AppPrefs.DEV_MODE, value).apply()
            _devMode.value = value
        }

    // ─── Audio mirroring (issue #72, Refs #13) ───
    //
    // 默认开（issue #13 决策记录 3）。StateFlow 镜像 + setter 唯一写入，范式同 devMode。
    // 读取点是握手 request 构造（sendHandshake）与 S3 的 9640 绑定门，都按当前值取，
    // 故改动在下次握手（或旋转重握手）生效，不在当前流内热切换。

    private val _audioEnabled = MutableStateFlow(prefs.getBoolean(AppPrefs.AUDIO_ENABLED, true))
    val audioEnabledFlow: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    var audioEnabled: Boolean
        get() = prefs.getBoolean(AppPrefs.AUDIO_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(AppPrefs.AUDIO_ENABLED, value).apply()
            _audioEnabled.value = value
        }

    // ─── Startup video settings ───

    private val _startupDpi = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_DPI, 0))
    val startupDpiFlow: StateFlow<Int> = _startupDpi.asStateFlow()

    /**
     * Car-side startup DPI override. 0 = auto-calibrate via VideoConfig.calculateOptimalDpi
     * (the portrait-app-safe cap, default). Non-zero in [120, 480] bypasses the cap and is
     * sent to the phone as `dpiOverride` in HandshakeRequest; the phone uses it verbatim and
     * echoes it back as `vdDpi`. Read at handshake construction time, so a change takes effect
     * on the next connect (or mid-stream rotation re-handshake), not live.
     */
    var startupDpi: Int
        get() = prefs.getInt(AppPrefs.STARTUP_DPI, 0)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_DPI, value).apply()
            _startupDpi.value = value
        }

    private val _startupFps = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_FPS, VideoConfig.TARGET_FPS))
    val startupFpsFlow: StateFlow<Int> = _startupFps.asStateFlow()

    /**
     * Persisted startup FPS. Unlike DPI/bitrate this one is also applied to the
     * live [CarConnectionService.targetFps] immediately — go through
     * [CarConnectionService.setStartupFps] when changing it at runtime.
     */
    var startupFps: Int
        get() = prefs.getInt(AppPrefs.STARTUP_FPS, VideoConfig.TARGET_FPS)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_FPS, value).apply()
            _startupFps.value = value
        }

    private val _startupBitrate = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_BITRATE, VideoConfig.DEFAULT_BITRATE))
    val startupBitrateFlow: StateFlow<Int> = _startupBitrate.asStateFlow()

    var startupBitrate: Int
        get() = prefs.getInt(AppPrefs.STARTUP_BITRATE, VideoConfig.DEFAULT_BITRATE)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_BITRATE, value).apply()
            _startupBitrate.value = value
        }

    // ─── 天气（issue #41 / #154）───
    //
    // 60s API 的 base URL 必须可配置。默认用自建镜像：官方 `60s.viki.moe` 的 Cloudflare
    // 对单 IP 限流很紧（连续请求即 `429 / error code: 1015 / Retry-After: 8`，2026-11-14 实测），
    // 镜像同源同构且实测不限流。要改回官方只需把 DEFAULT_WEATHER_API_BASE_URL 换回
    // `https://60s.viki.moe/v2/weather`（issue #154）。
    // 缓存字段（city/condition/temperature/updated_at）让冷启动先渲染上次成功值再异步刷新，
    // 断网时保留旧值（验收标准 2）。

    /** 60s 天气 API base URL，默认自建镜像（官方限流，见上方注释）。可换成任意同构实例。 */
    var weatherApiBaseUrl: String
        get() = prefs.getString(WEATHER_API_BASE_URL, DEFAULT_WEATHER_API_BASE_URL)
            ?: DEFAULT_WEATHER_API_BASE_URL
        set(value) = prefs.edit().putString(WEATHER_API_BASE_URL, value).apply()

    /** 缓存的城市名（上次成功拉取的 `data.location.name`）。 */
    var weatherCity: String
        get() = prefs.getString(WEATHER_CITY, "") ?: ""
        set(value) = prefs.edit().putString(WEATHER_CITY, value).apply()

    /** 缓存的天气现象（上次成功的 `data.weather.condition`）。 */
    var weatherCondition: String
        get() = prefs.getString(WEATHER_CONDITION, "") ?: ""
        set(value) = prefs.edit().putString(WEATHER_CONDITION, value).apply()

    /** 缓存的温度（上次成功的 `data.weather.temperature`）。 */
    var weatherTemperature: Int
        get() = prefs.getInt(WEATHER_TEMPERATURE, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(WEATHER_TEMPERATURE, value).apply()

    /** 缓存的更新时间戳（上次成功的 `data.weather.updated_at`，ms）。0 = 从未成功。 */
    var weatherUpdatedAtMs: Long
        get() = prefs.getLong(WEATHER_UPDATED_AT, 0L)
        set(value) = prefs.edit().putLong(WEATHER_UPDATED_AT, value).apply()

    companion object {
        // CarPrefs 是 internal，但 prefs key 跨实例共享同一个文件，放 companion 让测试可引用。
        internal const val WEATHER_API_BASE_URL = "weather_api_base_url"
        internal const val DEFAULT_WEATHER_API_BASE_URL = "https://60s.yuuverne.site/v2/weather"
        internal const val WEATHER_CITY = "weather_city"
        internal const val WEATHER_CONDITION = "weather_condition"
        internal const val WEATHER_TEMPERATURE = "weather_temperature"
        internal const val WEATHER_UPDATED_AT = "weather_updated_at"
    }
}
