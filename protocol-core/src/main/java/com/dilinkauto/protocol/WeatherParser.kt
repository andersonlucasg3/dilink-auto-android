package com.dilinkauto.protocol

/**
 * 60s 天气 API 的 DTO 与解析逻辑（issue #41）。
 *
 * API：`https://60s.viki.moe/v2/weather`（不传 query 时按请求方 IP 定位）。
 * 响应结构（2026-11-02 实测确认）：
 * ```
 * { "code":200, "message":"...", "data":{
 *     "location":{ "name":"北京北京", "province":"...", "city":"...", "county":"" },
 *     "weather":{ "condition":"晴", "condition_code":"00", "temperature":6,
 *                 "humidity":74, "pressure":1016, "precipitation":0,
 *                 "wind_direction":"东南风", "wind_power":"3-4",
 *                 "weather_icon":"...", "weather_colors":[...],
 *                 "updated":"2026-11-02 22:10:00", "updated_at":1793657400000 },
 *     "air_quality":{...}, "sunrise":{...}, "alerts":[...]
 * }}
 * ```
 *
 * v1 只取 `code` + `data.location.name` + `data.weather.condition/temperature/updated_at`，
 * 其余字段（air_quality / sunrise / alerts / humidity / pressure / wind）一律忽略 ——
 * issue #41 验收标准只要「城市 + 现象 + 温度」。DTO 字段用可空类型，缺字段时降级而不抛。
 *
 * 落 `protocol-core` 一格（纯 JVM + Gson + JUnit，与 `ClockFormat.kt` / `AltitudeFormat.kt` 同格），
 * 不依赖 Android API。
 */
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/** 解析成功的天气数据（已校验 code==200）。 */
data class WeatherData(
    val city: String,
    val condition: String,
    val temperature: Int,
    val updatedAtMs: Long
)

/** 60s API 响应的最外层。 */
private data class WeatherResponse(
    @SerializedName("code") val code: Int?,
    @SerializedName("data") val data: WeatherResponseData?
)

private data class WeatherResponseData(
    @SerializedName("location") val location: WeatherLocation?,
    @SerializedName("weather") val weather: WeatherInfo?
)

private data class WeatherLocation(
    @SerializedName("name") val name: String?
)

private data class WeatherInfo(
    @SerializedName("condition") val condition: String?,
    @SerializedName("temperature") val temperature: Int?,
    @SerializedName("updated_at") val updatedAt: Long?
)

object WeatherParser {
    private val gson = Gson()

    /**
     * 解析 60s API JSON。
     *
     * - `code != 200` → null（接口报错，issue #41 失败态）。
     * - 任一关键字段缺失 → null（字段缺失按失败处理，不补占位假值）。
     * - Gson 解析异常 → null（不抛，调用方按失败退避）。
     *
     * @return [WeatherData] 或 null。
     */
    fun parse(json: String): WeatherData? {
        return try {
            val resp = gson.fromJson(json, WeatherResponse::class.java) ?: return null
            if (resp.code != 200) return null
            val data = resp.data ?: return null
            val weather = data.weather ?: return null
            val condition = weather.condition
            val temperature = weather.temperature
            val updatedAt = weather.updatedAt
            // 三要素任一缺失即视为无效（issue #41「不得显示估算或占位假值」）。
            if (condition.isNullOrEmpty() || temperature == null || updatedAt == null) return null
            WeatherData(
                city = data.location?.name.orEmpty(),
                condition = condition,
                temperature = temperature,
                updatedAtMs = updatedAt
            )
        } catch (_: Exception) {
            null
        }
    }
}
