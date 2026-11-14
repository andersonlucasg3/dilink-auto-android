package com.dilinkauto.server.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.dilinkauto.protocol.WeatherData
import com.dilinkauto.protocol.WeatherFormat
import com.dilinkauto.protocol.WeatherParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 车机主屏天气的 UI 状态（issue #41）。
 *
 * `null` 表示从未成功拉取（首次安装且从未联网），UI 渲染 `—`。
 * [isStale] 标志距上次成功 > 60 分钟或本次刷新失败，驱动 chip 末尾加 ` *`。
 */
data class WeatherUiState(
    val city: String,
    val condition: String,
    val temperature: Int,
    val updatedAtMs: Long,
    val isStale: Boolean
)

/**
 * 60s 天气 API 的拉取、缓存与调度（issue #41）。
 *
 * 把「HTTPS 拉取 + Gson 解析 + CarPrefs 缓存 + 退避轮询」从 [CarConnectionService] 拆出
 * 单独成类（SRP，仿 [ClockProvider] / [AltitudeProvider]）。服务只持有 `weatherState` 出口
 * 与 [start]/[stop] 两个生命周期调用。
 *
 * - 网络：`HttpURLConnection`（HTTPS，超时 5s/8s，**在 [Dispatchers.IO] 执行**——服务的
 *   scope 绑 `Dispatchers.Main`，留在主线程会被 `BlockGuard` 抛 `NetworkOnMainThreadException`）；
 *   `AndroidManifest.xml` 已是 `usesCleartextTraffic=false`，接口必须 HTTPS，不做 HTTP 回退。
 * - 解析：委托 [WeatherParser]（protocol-core，Gson，单测覆盖正常/code!=200/字段缺失/异常）。
 * - 调度：冷启动立即拉一次，成功后每 [WeatherFormat.REFRESH_INTERVAL_MS]（15 分钟）轮询；
 *   失败按 [WeatherFormat.resolveBackoffMs]（`nextBackoffMs` 30s→2m→5m→15m 与
 *   服务端 `Retry-After` 取较大值）退避，不形成请求风暴；无网走
 *   [WeatherFormat.NO_NETWORK_RETRY_MS]（60s）短重试且不计失败。
 * - 缓存：成功后写入 [CarPrefs]（city/condition/temperature/updated_at）；冷启动先渲染缓存
 *   再异步刷新，断网保留旧值（验收标准 2）。
 * - 离线空转防护：拉取前自查 [hasNetwork]，无网不拉、不递增失败计数（避免退避被离线拉满）。
 * - 失败态：HTTP 非 200 / `code != 200` / 解析异常 → 保留旧值，不弹窗、不覆盖缓存；
 *   从未成功 → null → UI 显示 `—`。
 *
 * 不新增运行时敏感权限（仅用已有 INTERNET / ACCESS_NETWORK_STATE）。
 */
internal class WeatherProvider(
    private val scope: CoroutineScope,
    private val context: Context,
    private val carPrefs: CarPrefs,
    private val sink: MutableStateFlow<WeatherUiState?>
) {
    /** 只读出口，供 UI `collectAsState`。 */
    val state: StateFlow<WeatherUiState?> = sink.asStateFlow()

    private var refreshJob: Job? = null

    fun start() {
        // 冷启动先渲染缓存（若有），再异步刷新。缓存有效（updated_at > 0）时按其是否过期标 stale。
        val cachedAt = carPrefs.weatherUpdatedAtMs
        if (cachedAt > 0L) {
            sink.value = WeatherUiState(
                city = carPrefs.weatherCity,
                condition = carPrefs.weatherCondition,
                temperature = carPrefs.weatherTemperature,
                updatedAtMs = cachedAt,
                isStale = WeatherFormat.isStale(cachedAt, System.currentTimeMillis())
            )
        }
        refreshJob = scope.launch {
            var failures = 0
            // 冷启动首轮立即拉；此后按「成功 15 分钟 / 失败退避 / 无网 60s」间隔循环。
            while (isActive) {
                if (!hasNetwork()) {
                    // 无网不计失败（避免离线把退避拉满），保留旧值并标 stale，60s 后再看。
                    markStaleIfCached()
                    delay(WeatherFormat.NO_NETWORK_RETRY_MS)
                    continue
                }
                when (val result = fetch()) {
                    is FetchResult.Success -> {
                        failures = 0
                        apply(result.data)
                        delay(WeatherFormat.REFRESH_INTERVAL_MS)
                    }
                    is FetchResult.Failure -> {
                        // 之前这里漏了递增：failures 恒为 0，退避序列是死代码，失败后一律干等 15 分钟（issue #154）。
                        failures++
                        markStaleIfCached()
                        val waitMs = WeatherFormat.resolveBackoffMs(failures, result.retryAfterMs)
                        Log.i(TAG, "refresh failed (#$failures), retry in ${waitMs}ms")
                        delay(waitMs)
                    }
                }
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /** 成功路径：写缓存 + 更新 sink + 记一行日志。 */
    private fun apply(data: WeatherData) {
        carPrefs.weatherCity = data.city
        carPrefs.weatherCondition = data.condition
        carPrefs.weatherTemperature = data.temperature
        carPrefs.weatherUpdatedAtMs = data.updatedAtMs
        sink.value = WeatherUiState(
            city = data.city,
            condition = data.condition,
            temperature = data.temperature,
            updatedAtMs = data.updatedAtMs,
            isStale = false
        )
        Log.i(TAG, "weather ok: city=${data.city} cond=${data.condition} temp=${data.temperature} updatedAt=${data.updatedAtMs}")
    }

    /**
     * 拉取并解析。成功返回 [FetchResult.Success]；失败返回 [FetchResult.Failure]，
     * 其中 `retryAfterMs` 来自 `429` 的 `Retry-After`（issue #154：限流要按服务端要求等待，
     * 不能按普通失败的退避硬等 30s+）。
     *
     * **必须切到 [Dispatchers.IO]**：服务的 scope 绑在 `Dispatchers.Main`
     * （`CarConnectionService.kt:38`），阻塞的 `HttpURLConnection` 若留在主线程会被
     * `BlockGuard` 抛 `NetworkOnMainThreadException`（issue #41 回签「天气恒 `—`」的真因）。
     */
    private suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(carPrefs.weatherApiBaseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                requestMethod = "GET"
                // 60s API 文档建议带 UA；不带也能用，带上更礼貌。
                setRequestProperty("User-Agent", "DiLinkAuto/1.0 (car)")
            }
            conn.use {
                val code = conn.responseCode
                if (code != 200) {
                    val retryAfterMs = WeatherFormat.parseRetryAfterMs(conn.getHeaderField("Retry-After"))
                    Log.w(TAG, "fetch failed: HTTP $code retryAfterMs=${retryAfterMs ?: "-"} url=${carPrefs.weatherApiBaseUrl}")
                    return@withContext FetchResult.Failure(retryAfterMs)
                }
                val data = conn.inputStream.bufferedReader().use { reader ->
                    WeatherParser.parse(reader.readText())
                }
                if (data == null) {
                    Log.w(TAG, "fetch failed: parse returned null url=${carPrefs.weatherApiBaseUrl}")
                    FetchResult.Failure(null)
                } else {
                    FetchResult.Success(data)
                }
            }
        } catch (e: Exception) {
            // 关键：这里曾静默吞掉 NetworkOnMainThreadException，导致回签时无线索（issue #41）。
            Log.w(TAG, "fetch failed: ${e.javaClass.simpleName} url=${carPrefs.weatherApiBaseUrl}", e)
            FetchResult.Failure(null)
        }
    }

    /** 一次拉取的结果：成功携带数据；失败携带服务端 `Retry-After`（若有，毫秒）。 */
    private sealed class FetchResult {
        class Success(val data: WeatherData) : FetchResult()
        class Failure(val retryAfterMs: Long?) : FetchResult()
    }

    /** 若已有缓存，把 sink 标 stale（断网/失败时保留旧值并提示过时）；无缓存则保持 null。 */
    private fun markStaleIfCached() {
        val cachedAt = carPrefs.weatherUpdatedAtMs
        if (cachedAt <= 0L) return
        val current = sink.value
        if (current == null || !current.isStale) {
            sink.value = WeatherUiState(
                city = carPrefs.weatherCity,
                condition = carPrefs.weatherCondition,
                temperature = carPrefs.weatherTemperature,
                updatedAtMs = cachedAt,
                isStale = true
            )
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 把 [HttpURLConnection] 的 disconnect 放进 finally 的小工具。 */
    private inline fun <T> HttpURLConnection.use(block: () -> T): T {
        try {
            return block()
        } finally {
            disconnect()
        }
    }

    private companion object {
        const val TAG = "WeatherProvider"
    }
}
