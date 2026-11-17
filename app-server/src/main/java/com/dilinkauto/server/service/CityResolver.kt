package com.dilinkauto.server.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dilinkauto.protocol.CityFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 天气城市解析（issue #157）。
 *
 * 60s 接口不接受坐标，只接受城市名，所以「GPS 坐标 → 城市名」必须自己做。解析顺序：
 *
 * 1. **GPS/网络定位 last known location → 高德 regeo**（需 key：`BuildConfig.AMAP_KEY` 或
 *    `CarPrefs.amapKey`）。这是「按 GPS 出城市」的主路径。
 * 2. **IP 城市**（`https://myip.ipip.net` 文本）：无 key / 无定位 / 反查失败时兜底。
 *    车机实测该服务返回「中国 广东 广州」，而 60s 自带的 IP 库会把同一出口 IP 判成北京
 *    （issue #157 的证据链），所以不能交给 60s 猜。
 * 3. 都失败 → null → 调用方退回「不带 query」的旧行为（天气照常出，只是城市可能不准）。
 *
 * 缓存：城市粒度变化慢，成功结果缓存 [CityFormat.CITY_TTL_MS]（6h）；若 GPS 位置相对上次
 * 解析点位移超过 [CityFormat.CITY_RELOCATE_METERS]（50km）则提前重解。
 *
 * 线程：所有定位与网络调用都在 [Dispatchers.IO]，服务的 scope 绑 Main（见 issue #41）。
 */
internal class CityResolver(
    private val context: Context,
    private val carPrefs: CarPrefs,
    private val log: (String) -> Unit = { Log.i(TAG, it) }
) {

    /** 解析当前城市；失败返回 null（调用方不应阻断天气刷新）。 */
    suspend fun resolve(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = carPrefs.weatherQueryCity.takeIf { it.isNotBlank() }
        val cachedAt = carPrefs.weatherQueryCityAtMs
        val location = currentLocation()

        if (cached != null && now - cachedAt in 0 until CityFormat.CITY_TTL_MS && !movedFar(location)) {
            return@withContext cached
        }

        // 1) GPS 坐标 → 高德反查（需要 key）。
        val amapKey = carPrefs.amapKey
        if (amapKey.isNotBlank() && location != null) {
            reverseGeocodeWithAmap(amapKey, location)?.let { city ->
                saveCity(city, location)
                log("city resolved by gps: $city (${location.latitude},${location.longitude})")
                return@withContext city
            }
            log("amap reverse geocode failed, falling back to ip city")
        } else if (amapKey.isBlank()) {
            log("amap key not configured, using ip city")
        }

        // 2) IP 城市兜底。
        ipCity(amapKey)?.let { city ->
            saveCity(city, location)
            log("city resolved by ip: $city")
            return@withContext city
        }

        // 3) 全部失败：沿用旧缓存（可能过期，但比没有强），否则 null。
        log("city resolve failed, cached=${cached ?: "-"}")
        cached
    }

    /** 读取当前定位：last known（GPS → 网络）→ 都为空时短请求一次（3s）。无权限返回 null。 */
    private suspend fun currentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "location permission not granted")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val cached = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.w(TAG, "getLastKnownLocation failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (cached != null) {
            Log.i(TAG, "location from cache: ${cached.latitude},${cached.longitude} provider=${cached.provider}")
            return cached
        }
        // 部分车机冷启动后 provider 缓存为空（AltitudeProvider 的监听还没回填），短请求一次拿首帧。
        val fresh = requestFreshLocation(lm)
        Log.i(TAG, "location from fresh request: ${fresh?.let { "${it.latitude},${it.longitude}" } ?: "none"}")
        return fresh
    }

    /** 最多等 3s 拿一次 GPS 定位；超时/异常返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(lm: LocationManager): Location? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit

                @Deprecated("API 29 起弃用，仍需实现")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates failed: ${e.javaClass.simpleName}", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** 缓存点与当前点位移是否超过阈值（当前无定位时不算移动）。 */
    private fun movedFar(location: Location?): Boolean {
        if (location == null) return false
        val lat = carPrefs.weatherQueryLat.toDouble()
        val lon = carPrefs.weatherQueryLon.toDouble()
        if (lat == 0.0 && lon == 0.0) return false
        return CityFormat.distanceMeters(lat, lon, location.latitude, location.longitude) >
            CityFormat.CITY_RELOCATE_METERS
    }

    /** 高德逆地理编码：`/v3/geocode/regeo`（注意 `location` 是「经度,纬度」）。 */
    private fun reverseGeocodeWithAmap(key: String, location: Location): String? {
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
            "?key=${URLEncoder.encode(key, "UTF-8")}" +
            "&location=${location.longitude},${location.latitude}" +
            "&extensions=base"
        return httpGet(url)?.let { CityFormat.parseAmapCity(it) }
    }

    /**
     * IP 城市兜底。无 key 时按可达性依次尝试：
     *
     * - `myip.ipip.net`（文本，中文城市）：车机实测偶发「连上但不回包」超时，所以放在第一个并给短超时；
     * - `ipwho.is`（JSON，`lang=zh-CN` 返回中文城市）：实测可用。
     *
     * 注意 IP 城市本质不可靠：本机双出口时同一分钟内 IP 会在广州/深圳间漂移，仅作兜底。
     */
    private fun ipCity(key: String): String? {
        amapIpCity(key)?.let { return it }
        httpGet("https://myip.ipip.net", readTimeoutMs = 5_000)
            ?.let { CityFormat.parseIpCity(it) }
            ?.let { return it }
        return httpGet("https://ipwho.is/?lang=zh-CN", readTimeoutMs = 6_000)
            ?.let { CityFormat.parseIpwhoCity(it) }
    }

    /** 有 key 时先用高德 IP 定位（服务端按请求方 IP 判城市，稳定且中文）。 */
    private fun amapIpCity(key: String): String? {
        if (key.isBlank()) return null
        val url = "https://restapi.amap.com/v3/ip?key=${URLEncoder.encode(key, "UTF-8")}"
        return httpGet(url)?.let { CityFormat.parseAmapIpCity(it) }
    }

    /** 极简 HTTPS GET，5s 连接 / 默认 8s 读；失败返回 null，不抛。 */
    private fun httpGet(url: String, readTimeoutMs: Int = 8_000): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "DiLinkAuto/1.0 (car)")
                // 部分 WAF 对 gzip 透明解压路径会挂住（车机实测 ipip 偶发 0 字节），显式关掉。
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "http ${conn.responseCode} url=$url")
                    return null
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "http failed: ${e.javaClass.simpleName} url=$url", e)
            null
        }
    }

    private fun saveCity(city: String, location: Location?) {
        carPrefs.weatherQueryCity = city
        carPrefs.weatherQueryCityAtMs = System.currentTimeMillis()
        if (location != null) {
            carPrefs.weatherQueryLat = location.latitude.toFloat()
            carPrefs.weatherQueryLon = location.longitude.toFloat()
        }
    }

    private companion object {
        const val TAG = "CityResolver"
    }
}
