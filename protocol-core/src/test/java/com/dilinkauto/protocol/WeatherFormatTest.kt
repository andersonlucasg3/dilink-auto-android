package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死车机主屏天气的纯解析 / 格式化 / 过期判定 / 退避逻辑（issue #41）。
 *
 * 用 60s API 实测响应（2026-11-02，HTTP 200，`code=200`、`condition=晴`、`temperature=6`、
 * `updated_at=1793657400000`）作为固定夹具，另覆盖 `code != 200` / 字段缺失 / 退避序列。
 */
class WeatherFormatTest {

    // 60s API 实测响应（精简到本测试关心的字段，结构保留外层 data 嵌套）。
    private val okJson = """
        {"code":200,"message":"获取成功。","data":{
          "location":{"name":"北京北京","province":"北京省","city":"北京市","county":""},
          "weather":{"condition":"晴","condition_code":"00","temperature":6,
                      "humidity":74,"pressure":1016,"precipitation":0,
                      "wind_direction":"东南风","wind_power":"3-4",
                      "weather_icon":"https://mat1.gtimg.com/qqcdn/xw/tianqi/bigIcon/heiye/00.png",
                      "weather_colors":["#151B33","#6D6581","#DFAB9A"],
                      "updated":"2026-11-02 22:10:00","updated_at":1793657400000},
          "air_quality":{},"sunrise":{},"alerts":[]}}
    """.trimIndent()

    // ─── WeatherParser ───

    @Test
    fun parsesOkResponse() {
        val data = WeatherParser.parse(okJson)
        assertEquals("北京北京", data?.city)
        assertEquals("晴", data?.condition)
        assertEquals(6, data?.temperature)
        assertEquals(1793657400000L, data?.updatedAtMs)
    }

    @Test
    fun returnsNullWhenCodeNot200() {
        // code=500 接口报错 → null。
        val badCodeJson = """{"code":500,"message":"failed","data":null}"""
        assertNull(WeatherParser.parse(badCodeJson))
    }

    @Test
    fun returnsNullWhenWeatherMissing() {
        // data.location 在但 data.weather 缺失 → null（关键字段缺失按失败处理，不补占位）。
        val noWeatherJson = """
            {"code":200,"data":{"location":{"name":"北京"},"weather":null}}
        """.trimIndent()
        assertNull(WeatherParser.parse(noWeatherJson))
    }

    @Test
    fun returnsNullWhenTemperatureMissing() {
        // temperature 字段缺失 → null（不显示估算假值，issue #41 验收标准 2）。
        val noTempJson = """
            {"code":200,"data":{"location":{"name":"北京"},
              "weather":{"condition":"晴","updated_at":1793657400000}}}
        """.trimIndent()
        assertNull(WeatherParser.parse(noTempJson))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        // 非 JSON 文本 → Gson 抛 → null，不向调用方传播异常。
        assertNull(WeatherParser.parse("not json at all"))
    }

    @Test
    fun emptyCityIsAllowedButConditionIsNot() {
        // city 可空（定位失败时 name 可能为空），condition/temperature/updated_at 不可缺。
        val emptyCityJson = """
            {"code":200,"data":{"location":{"name":""},
              "weather":{"condition":"晴","temperature":6,"updated_at":1793657400000}}}
        """.trimIndent()
        val data = WeatherParser.parse(emptyCityJson)
        assertEquals("", data?.city)
        assertEquals("晴", data?.condition)
    }

    // ─── WeatherFormat.formatWeather ───

    @Test
    fun nullRendersAsDash() {
        assertEquals("—", WeatherFormat.formatWeather(null, isStale = false))
    }

    @Test
    fun formatsConditionAndTemperature() {
        val data = WeatherData(city = "北京", condition = "晴", temperature = 6, updatedAtMs = 0L)
        assertEquals("晴 6°C", WeatherFormat.formatWeather(data, isStale = false))
    }

    @Test
    fun staleAppendsAsterisk() {
        val data = WeatherData(city = "北京", condition = "晴", temperature = 6, updatedAtMs = 0L)
        assertEquals("晴 6°C *", WeatherFormat.formatWeather(data, isStale = true))
    }

    @Test
    fun negativeTemperatureFormats() {
        val data = WeatherData(city = "哈尔滨", condition = "小雪", temperature = -3, updatedAtMs = 0L)
        assertEquals("小雪 -3°C", WeatherFormat.formatWeather(data, isStale = false))
    }

    // ─── WeatherFormat.isStale ───

    @Test
    fun freshDataIsNotStale() {
        // 刚好 60 分钟 → 不过期（含边界）。
        val updatedAt = 0L
        val now = WeatherFormat.STALE_THRESHOLD_MS
        assertFalse(WeatherFormat.isStale(updatedAt, now))
    }

    @Test
    fun dataOlderThan60MinIsStale() {
        // 60 分钟 + 1ms → 过期。
        val updatedAt = 0L
        val now = WeatherFormat.STALE_THRESHOLD_MS + 1
        assertTrue(WeatherFormat.isStale(updatedAt, now))
    }

    @Test
    fun negativeAgeTreatedAsFresh() {
        // 时钟回拨或服务器时间在未来（now < updatedAt）→ 视为未过期。
        assertFalse(WeatherFormat.isStale(updatedAtMs = 1_000_000, nowMs = 0))
    }

    // ─── WeatherFormat.nextBackoffMs ───

    @Test
    fun backoffSequenceIs30s2m5m15m() {
        assertEquals(30_000L, WeatherFormat.nextBackoffMs(0))
        assertEquals(30_000L, WeatherFormat.nextBackoffMs(1))
        assertEquals(120_000L, WeatherFormat.nextBackoffMs(2))
        assertEquals(300_000L, WeatherFormat.nextBackoffMs(3))
        assertEquals(900_000L, WeatherFormat.nextBackoffMs(4))
    }

    @Test
    fun backoffCapsAt15Minutes() {
        // 第 5 次及以后恒为 15m，不继续增长，不形成请求风暴。
        assertEquals(900_000L, WeatherFormat.nextBackoffMs(5))
        assertEquals(900_000L, WeatherFormat.nextBackoffMs(100))
    }

    // ─── WeatherFormat.resolveBackoffMs（退避 vs Retry-After 取大，issue #154）───

    @Test
    fun resolveBackoffWithoutRetryAfterUsesSequence() {
        assertEquals(30_000L, WeatherFormat.resolveBackoffMs(1, null))
        assertEquals(120_000L, WeatherFormat.resolveBackoffMs(2, null))
        assertEquals(900_000L, WeatherFormat.resolveBackoffMs(9, null))
    }

    @Test
    fun resolveBackoffHonoursLongerRetryAfter() {
        // 服务端要求 60s，退避只有 30s → 听服务端的。
        assertEquals(60_000L, WeatherFormat.resolveBackoffMs(1, 60_000L))
    }

    @Test
    fun resolveBackoffKeepsOwnBackoffWhenRetryAfterShorter() {
        // 官方实例 429 实测 Retry-After: 8（8s），退避 30s 更长 → 按 30s，避免打得太密。
        assertEquals(30_000L, WeatherFormat.resolveBackoffMs(1, 8_000L))
    }

    @Test
    fun resolveBackoffClampsAbsurdRetryAfter() {
        // 异常大值按 15 分钟上限截断，不把刷新挂死。
        assertEquals(900_000L, WeatherFormat.resolveBackoffMs(1, 86_400_000L))
    }

    // ─── WeatherFormat.parseRetryAfterMs ───

    @Test
    fun parsesRetryAfterSeconds() {
        assertEquals(8_000L, WeatherFormat.parseRetryAfterMs("8"))
        assertEquals(8_000L, WeatherFormat.parseRetryAfterMs(" 8 "))
        assertEquals(0L, WeatherFormat.parseRetryAfterMs("0"))
    }

    @Test
    fun rejectsRetryAfterOutsideSecondsForm() {
        // HTTP-date 形式不解析（60s 只发秒数），非法值与负值一律 null。
        assertNull(WeatherFormat.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(WeatherFormat.parseRetryAfterMs("-5"))
        assertNull(WeatherFormat.parseRetryAfterMs("abc"))
        assertNull(WeatherFormat.parseRetryAfterMs(""))
        assertNull(WeatherFormat.parseRetryAfterMs(null))
    }

    @Test
    fun parseRetryAfterClampsHugeValue() {
        assertEquals(900_000L, WeatherFormat.parseRetryAfterMs("86400"))
    }
}
