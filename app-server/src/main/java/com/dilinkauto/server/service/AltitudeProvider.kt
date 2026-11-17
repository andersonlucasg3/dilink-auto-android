package com.dilinkauto.server.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.dilinkauto.protocol.AltitudeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 车机主屏海拔的 UI 状态（issue #40）。
 *
 * `null` 表示无有效数据（无定位权限 / 无卫星 / 传感器缺失），UI 渲染 `—`；
 * 恢复定位后自动回填数值（issue #40 验收标准 2）。
 */
data class AltitudeUiState(val meters: Int?)

/**
 * GPS 海拔采样与平滑（issue #40）。
 *
 * 把「注册 GPS listener + 判定有效定位 + EMA 平滑 + 取整」从 [CarConnectionService] 拆出
 * 单独成类（SRP，仿 [ClockProvider]）。服务只持有 `altitudeState` 出口与 [start]/[stop]
 * 两个生命周期调用。
 *
 * - 数据源：`LocationManager.GPS_PROVIDER` → `Location.getAltitude()`（米，WGS84 椭球高）。
 *   `requestLocationUpdates(1000ms, 0f, listener)`，只接受 [AltitudeFormat.acceptLocation] 为真的结果，
 *   避免拿过期缓存冒充实时（地库/隧道失锁场景）。
 * - 输入闸门（issue #50）：先过 [AltitudeFormat.acceptVerticalAccuracy]（垂直精度 >100m 拒收），
 *   再用 `elapsedRealtimeNanos` 做跳变闸门 [AltitudeFormat.isJumpPlausible]（物理不可能跳变拒收）。
 * - bootstrap（issue #50）：首样本不直采上屏，先缓存最近样本；凑齐 3 个两两差 ≤30m 的样本后
 *   取中位数初始化，再进入 EMA，避免冷启动瞬间把异常点显示出来。
 * - 平滑：EMA α=0.2 后四舍五入到整数米，避免 GPS 抖动（±10–20m）导致数字每帧跳。
 * - 失败态：权限拒绝 / Provider 关闭 / 无卫星 → `null` → UI 显示 `—`；恢复定位后自动回填。
 * - 不新增权限（`ACCESS_FINE_LOCATION` 已在 `AndroidManifest.xml:11` 声明），
 *   不引入第三方定位 SDK（不新增 Google Play Services 依赖）。
 *
 * 权限由 [com.dilinkauto.server.MainActivity] 在运行时一次性请求；本类在 [start] 时自查
 * `checkSelfPermission`，未授权则不注册 listener，直接保持 null。
 */
internal class AltitudeProvider(
    private val scope: CoroutineScope,
    private val context: Context,
    private val sink: MutableStateFlow<AltitudeUiState?>,
    /** 诊断日志出口（默认 no-op，便于单测）；实际由 [CarConnectionService] 注入 `carLogSend`，
     *  沿用 [CarLogWriter] 的开关，release 构建默认不产生噪声。 */
    private val log: (String) -> Unit = {}
) {
    /** 只读出口，供 UI `collectAsState`。 */
    val state: StateFlow<AltitudeUiState?> = sink.asStateFlow()

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    @Volatile private var smoothed: Double? = null

    /** bootstrap 滑动窗口（issue #50）：未初始化前缓存最近 [AltitudeFormat.BOOTSTRAP_SAMPLES] 个样本。 */
    private val bootstrapSamples = ArrayDeque<Double>()

    /** 最近一个「已采纳」样本的原始海拔（米）与其单调时钟时间戳，仅用于跳变闸门。 */
    private var lastAcceptedMeters: Double = 0.0
    private var lastAcceptedElapsedNanos: Long = 0L

    /**
     * 启动 GPS 采样。权限未授予时安全降级为「不注册、保持 null」。
     *
     * `@SuppressLint("MissingPermission")`：权限在调用前已自查， lint 无法跨方法跟踪
     * [ContextCompat.checkSelfPermission] 的结果，此处显式声明已检查。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            // 未授权 → 海拔显示 `—`，不阻塞启动，不影响其他功能（issue #40 验收标准 2）。
            sink.value = AltitudeUiState(meters = null)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val provider = LocationManager.GPS_PROVIDER
        if (!lm.isProviderEnabled(provider)) {
            // GPS 关闭 → 同样降级为 null。
            sink.value = AltitudeUiState(meters = null)
            return
        }
        locationManager = lm
        // minTimeMs=1000（1Hz）、minDistanceM=0f（不按距离过滤，海拔随行驶缓慢变化需每秒采样）。
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }
            // 旧 API（< 30）仍可能走 onStatusChanged；minTime/minProvider 回调在 30+ 已弃用，
            // 这里只覆盖 onLocationChanged 即可，其余用默认空实现。
        }
        // requestLocationUpdates(long, float, LocationListener) 在 API 30 弃用但仍可用；
        // minSdk=26，用兼容签名避免引入额外的 PendingIntent 回路。
        @Suppress("DEPRECATION")
        lm.requestLocationUpdates(provider, 1000L, 0f, listener)
        this.listener = listener
    }

    fun stop() {
        listener?.let {
            try { locationManager?.removeUpdates(it) } catch (_: Exception) { /* already removed */ }
        }
        listener = null
        locationManager = null
    }

    /**
     * 权限授予后由 [com.dilinkauto.server.MainActivity] 的回调调用，重新尝试注册 listener。
     * 首次 [start] 时若未授权会保持 null；用户在系统对话框授权后调此方法恢复采样。
     */
    fun onPermissionResult() {
        if (listener == null) start()
    }

    private fun handleLocation(location: Location) {
        // 年龄判定仍用系统时钟（与 acceptLocation 的语义一致）；跳变闸门用单调时钟
        // elapsedRealtimeNanos，避免用户改系统时间（issue #39 场景）让 Δt 失真。
        val ageMs = System.currentTimeMillis() - location.time
        val altitude = if (location.hasAltitude()) location.altitude else null
        val hasVAcc = location.hasVerticalAccuracy()
        val vAcc = if (hasVAcc) location.verticalAccuracyMeters else Float.NaN
        val elapsedNanos = location.elapsedRealtimeNanos

        fun diagnose(accepted: Boolean, reason: String) {
            // 每样本一行（1Hz），由 CarLogWriter 的开关控制；release 默认关闭，不产生噪声。
            val altText = altitude?.let { String.format(Locale.US, "%.1f", it) } ?: "none"
            val vAccText = if (hasVAcc) String.format(Locale.US, "%.1f", vAcc) else "none"
            log("[Altitude] alt=$altText vAcc=$vAccText ageMs=$ageMs " +
                "accept=$accepted reason=$reason")
        }

        if (altitude == null) {
            diagnose(accepted = false, reason = "no-altitude")
            return
        }
        if (!AltitudeFormat.acceptLocation(altitude, ageMs)) {
            // 过期缓存 → 不更新平滑值、不更新时间戳，也不清空（避免地库短暂失锁就跳 `—`）。
            diagnose(accepted = false, reason = "stale")
            return
        }
        if (!AltitudeFormat.acceptVerticalAccuracy(hasVAcc, vAcc)) {
            // 垂直精度太差（>100m）→ 拒收，保持上一次有效显示。
            diagnose(accepted = false, reason = "vertical-accuracy")
            return
        }

        if (smoothed == null) {
            // ── bootstrap（issue #50）：前 3 个稳定样本取中位数初始化，首样本不直采上屏 ──
            bootstrapSamples.addLast(altitude)
            while (bootstrapSamples.size > AltitudeFormat.BOOTSTRAP_SAMPLES) {
                bootstrapSamples.removeFirst()
            }
            if (!AltitudeFormat.bootstrapReady(bootstrapSamples)) {
                diagnose(accepted = false, reason = "bootstrap-wait")
                return
            }
            val initial = AltitudeFormat.medianOfThree(
                bootstrapSamples[0], bootstrapSamples[1], bootstrapSamples[2]
            )
            smoothed = initial
            lastAcceptedMeters = altitude
            lastAcceptedElapsedNanos = elapsedNanos
            sink.value = AltitudeUiState(meters = initial.toInt())
            diagnose(accepted = true, reason = "bootstrap-init")
            return
        }

        // ── 跳变闸门（issue #50）：相对最近一次「已采纳」样本做物理合理性判断 ──
        if (!AltitudeFormat.isJumpPlausible(
                lastAcceptedMeters, lastAcceptedElapsedNanos, altitude, elapsedNanos
            )
        ) {
            // 物理不可能跳变 → 不更新平滑值、不更新时间戳。
            diagnose(accepted = false, reason = "jump")
            return
        }

        smoothed = AltitudeFormat.emaSmooth(smoothed, altitude)
        lastAcceptedMeters = altitude
        lastAcceptedElapsedNanos = elapsedNanos
        sink.value = AltitudeUiState(meters = smoothed!!.toInt())
        diagnose(accepted = true, reason = "ema")
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
