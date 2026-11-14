package com.dilinkauto.protocol

import com.google.gson.JsonParser
import java.net.URLEncoder

/**
 * 天气城市定位的纯函数（issue #157）。
 *
 * 60s 接口不接受坐标（`?query=23.11,113.46` → `code=404 未找到城市`），只接受城市名；
 * 因此「GPS 坐标 → 城市名」的反向地理编码必须自己做。本文件只放**可单测的纯逻辑**：
 * 解析高德 regeo 回包、解析 ipip 文本、城市名归一化、拼 `?query=` URL。
 *
 * 网络与定位权限在 `app-server` 的 `CityResolver` 里（Android 依赖不进来）。
 */
object CityFormat {

    /** 城市缓存有效期：6 小时（城市粒度，跨城才会变）。 */
    const val CITY_TTL_MS = 6L * 60L * 60_000L

    /** GPS 位移超过该距离（米）时提前重解城市：50km。 */
    const val CITY_RELOCATE_METERS = 50_000.0

    /**
     * 解析 `https://myip.ipip.net` 的文本回包：
     * `当前 IP：103.27.26.76  来自于：中国 广东 广州  广东广电` → `广州`。
     *
     * 取「来自于：」之后的第 3 段（国家 / 省 / 市 / 运营商）。段数不足或格式变化返回 null，
     * 调用方回落到「不带 query」的旧行为，不猜。
     */
    fun parseIpCity(body: String?): String? {
        val text = body?.trim().orEmpty()
        val marker = "来自于："
        val idx = text.indexOf(marker)
        if (idx < 0) return null
        val parts = text.substring(idx + marker.length)
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null
        return normalizeCity(parts[2])
    }

    /**
     * 解析高德 `/v3/geocode/regeo` 回包，取 `regeocode.addressComponent.city`；
     * 直辖市该字段是空数组（非字符串），此时退到 `province`（如「北京市」→「北京」）。
     *
     * `status != "1"` / 结构缺失 → null。
     */
    fun parseAmapCity(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.get("status")?.takeIf { it.isJsonPrimitive }?.asString != "1") return null
            val components = root.getAsJsonObject("regeocode")
                ?.getAsJsonObject("addressComponent")
                ?: return null
            val city = components.get("city").asOptionalString()
            val province = components.get("province").asOptionalString()
            normalizeCity(city ?: province)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 城市名归一化：去空白、去掉尾部「市」。
     *
     * `广州市` → `广州`（60s 的 `?query=` 两种都认，统一成短名便于缓存比较）；
     * `北京市` → `北京`；`恩施土家族苗族自治州` 这类不含「市」的原样返回。
     */
    fun normalizeCity(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val stripped = trimmed.removeSuffix("市").trim()
        return stripped.ifEmpty { null }
    }

    /**
     * 把城市拼到天气 URL 的 `query` 参数上。
     *
     * - `city == null`（定位失败）→ 原样返回 base，走 60s 的 IP 定位（旧行为，不阻断天气）。
     * - base 已带查询串（含 `?`）时用 `&` 接，避免拼出两个 `?`。
     */
    fun buildWeatherUrl(baseUrl: String, city: String?): String {
        val base = baseUrl.trim()
        val name = normalizeCity(city) ?: return base
        val separator = if (base.contains('?')) '&' else '?'
        return base + separator + "query=" + URLEncoder.encode(name, "UTF-8")
    }

    /**
     * 解析 `https://ipwho.is/?lang=zh-CN` 的 JSON（无 key 备选源）：
     * `{"success":true,"country":"中国","region":"广东省","city":"深圳"}` → `深圳`。
     *
     * `success=false` 或 city 缺失 → null。
     */
    fun parseIpwhoCity(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return null
            normalizeCity(root.get("city").asOptionalString())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析高德 IP 定位 `/v3/ip` 回包：`{"status":"1","province":"广东省","city":"广州市"}` → `广州`。
     *
     * 与 regeo 的差别：字段在顶层，且直辖市 `city` 同样是空数组（退到 `province`）。
     */
    fun parseAmapIpCity(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.get("status")?.takeIf { it.isJsonPrimitive }?.asString != "1") return null
            val city = root.get("city").asOptionalString()
            val province = root.get("province").asOptionalString()
            normalizeCity(city ?: province)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 两点间球面距离（米），Haversine。
     *
     * 只用于「缓存城市是否需要重解」的判断（[CITY_RELOCATE_METERS]），不做导航精度用途。
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Gson 的 `JsonElement.asString` 只在字符串/数字等原始类型上可用，数组（直辖市 city）返回 null。 */
    private fun com.google.gson.JsonElement?.asOptionalString(): String? {
        val element = this ?: return null
        if (!element.isJsonPrimitive) return null
        val value = element.asString.trim()
        return value.ifEmpty { null }
    }
}
