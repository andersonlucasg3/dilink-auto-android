package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死天气城市定位的纯逻辑（issue #157）：
 * ipip 文本解析、高德 regeo 回包解析、城市名归一化、`?query=` URL 拼接。
 *
 * 夹具取自 2026-11-14 车机 MI 9 实测：
 * - `curl https://myip.ipip.net` → `当前 IP：103.27.26.76  来自于：中国 广东 广州  广东广电`
 * - 高德 regeo 结构按官方文档（`status` + `regeocode.addressComponent.city`，直辖市 city 为 `[]`）。
 */
class CityFormatTest {

    // ─── parseIpCity ───

    @Test
    fun parsesIpipCity() {
        val body = "当前 IP：103.27.26.76  来自于：中国 广东 广州  广东广电"
        assertEquals("广州", CityFormat.parseIpCity(body))
    }

    @Test
    fun parsesIpipMunicipality() {
        val body = "当前 IP：1.2.3.4  来自于：中国 北京 北京  联通"
        assertEquals("北京", CityFormat.parseIpCity(body))
    }

    @Test
    fun rejectsUnexpectedIpipBody() {
        assertNull(CityFormat.parseIpCity(null))
        assertNull(CityFormat.parseIpCity(""))
        assertNull(CityFormat.parseIpCity("<html>Just a moment...</html>"))
        // 段数不足（只有国家+省）→ 不猜。
        assertNull(CityFormat.parseIpCity("当前 IP：1.2.3.4  来自于：中国 广东"))
    }

    // ─── parseAmapCity ───

    @Test
    fun parsesAmapCity() {
        val json = """
            {"status":"1","info":"OK","regeocode":{
              "formatted_address":"广东省广州市天河区",
              "addressComponent":{"province":"广东省","city":"广州市","district":"天河区"}}}
        """.trimIndent()
        assertEquals("广州", CityFormat.parseAmapCity(json))
    }

    @Test
    fun fallsBackToProvinceForMunicipality() {
        // 直辖市 city 是空数组，不是字符串。
        val json = """
            {"status":"1","regeocode":{"addressComponent":{"province":"北京市","city":[]}}}
        """.trimIndent()
        assertEquals("北京", CityFormat.parseAmapCity(json))
    }

    @Test
    fun rejectsAmapErrorResponse() {
        assertNull(CityFormat.parseAmapCity("""{"status":"0","info":"INVALID_USER_KEY"}"""))
        assertNull(CityFormat.parseAmapCity("""{"status":"1"}"""))
        assertNull(CityFormat.parseAmapCity("not json"))
        assertNull(CityFormat.parseAmapCity(null))
    }

    // ─── normalizeCity ───

    @Test
    fun normalizesCitySuffix() {
        assertEquals("广州", CityFormat.normalizeCity("广州市"))
        assertEquals("北京", CityFormat.normalizeCity(" 北京市 "))
        assertEquals("恩施土家族苗族自治州", CityFormat.normalizeCity("恩施土家族苗族自治州"))
        assertNull(CityFormat.normalizeCity("   "))
        assertNull(CityFormat.normalizeCity(null))
    }

    // ─── buildWeatherUrl ───

    @Test
    fun appendsQueryWhenCityKnown() {
        assertEquals(
            "https://60s.yuuverne.site/v2/weather?query=%E5%B9%BF%E5%B7%9E",
            CityFormat.buildWeatherUrl("https://60s.yuuverne.site/v2/weather", "广州")
        )
    }

    @Test
    fun usesAmpersandWhenBaseAlreadyHasQuery() {
        assertEquals(
            "https://example.test/v2/weather?lang=zh&query=%E5%8C%97%E4%BA%AC",
            CityFormat.buildWeatherUrl("https://example.test/v2/weather?lang=zh", "北京市")
        )
    }

    @Test
    fun keepsBaseWhenCityUnknown() {
        val base = "https://60s.yuuverne.site/v2/weather"
        assertEquals(base, CityFormat.buildWeatherUrl(base, null))
        assertEquals(base, CityFormat.buildWeatherUrl(base, "   "))
    }

    // ─── parseIpwhoCity ───

    @Test
    fun parsesIpwhoCity() {
        val json = """
            {"ip":"120.233.71.95","success":true,"type":"IPv4","continent":"亚洲",
             "country":"中国","country_code":"CN","region":"广东省","city":"深圳"}
        """.trimIndent()
        assertEquals("深圳", CityFormat.parseIpwhoCity(json))
    }

    @Test
    fun rejectsIpwhoFailure() {
        assertNull(CityFormat.parseIpwhoCity("""{"success":false,"message":"reserved range"}"""))
        assertNull(CityFormat.parseIpwhoCity("""{"success":true,"country":"中国"}"""))
        assertNull(CityFormat.parseIpwhoCity("not json"))
    }

    // ─── parseAmapIpCity（/v3/ip，字段在顶层）───

    @Test
    fun parsesAmapIpCity() {
        val json = """{"status":"1","province":"广东省","adcode":"440000","city":"广州市"}"""
        assertEquals("广州", CityFormat.parseAmapIpCity(json))
    }

    @Test
    fun parsesAmapIpCityForMunicipality() {
        assertEquals("北京", CityFormat.parseAmapIpCity("""{"status":"1","province":"北京市","city":[]}"""))
    }

    @Test
    fun rejectsAmapIpError() {
        assertNull(CityFormat.parseAmapIpCity("""{"status":"0","info":"INVALID_USER_KEY"}"""))
        assertNull(CityFormat.parseAmapIpCity(null))
    }

    // ─── distanceMeters ───

    @Test
    fun distanceIsZeroForSamePoint() {
        assertEquals(0.0, CityFormat.distanceMeters(23.1103, 113.4593, 23.1103, 113.4593), 0.001)
    }

    @Test
    fun distanceGuangzhouToShenzhenIsAbout100km() {
        // 广州天河 → 深圳福田 约 95km（Haversine 的粗略校验，只用于 50km 重解阈值）。
        val d = CityFormat.distanceMeters(23.1103, 113.4593, 22.5410, 114.0550)
        assertTrue("实测 $d 米，期望 80~110km", d in 80_000.0..110_000.0)
        assertTrue(d > CityFormat.CITY_RELOCATE_METERS)
    }
}
