package com.dilinkauto.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 车机主屏海拔显示的纯判定 / 平滑 / 格式化逻辑（issue #40）。
 *
 * 与 [ClockFormat] 同格放在 protocol-core —— 「判定一个 GPS 定位是否新鲜有效」、
 * 「对海拔做指数滑动平均」、「把整数米渲染成 `1234m` / `-12m` / `—`」这三件事是纯函数，
 * 在 JVM 上就能用 JUnit 钉死，无需实车 GPS。
 *
 * 不读 Android API（[android.location.Location]、[android.content.pm.PackageManager]
 * 由调用方传入判定结果），单测用固定数值覆盖。
 */
object AltitudeFormat {

    /**
     * 一个有效定位的最大年龄（毫秒）。超过即视为过期缓存，拒收（issue #40 追评一）：
     * 车机进入地库/隧道失锁后，`LocationManager` 可能仍持有最后一次缓存定位，
     * 拿过期值冒充实时会导致海拔「卡住不动」。
     */
    const val MAX_LOCATION_AGE_MS = 10_000L

    /** EMA 默认平滑系数：新样本权重 0.2，历史权重 0.8。GPS 抖动 ±10–20m 经此平滑后数字不再每帧跳。 */
    const val DEFAULT_EMA_ALPHA = 0.2f

    /**
     * 垂直精度闸门上限（米，issue #50）。`Location.getVerticalAccuracyMeters()` 超过此值时，
     * 说明该样本的垂直解算置信度已低于 ~100m，拿来上屏会让海拔乱跳，直接拒收。
     */
    const val MAX_VERTICAL_ACCURACY_M = 100f

    /** 物理爬升速率上限（米/秒，issue #50）。民航客机爬升率约 3–4m/s，5m/s 留出余量。 */
    const val MAX_CLIMB_RATE_MPS = 5.0

    /** 跳变闸门的时间余量（米）。抵消 GPS 抖动，避免把 1 秒内的正常 ±50m 波动误判为跳变。 */
    const val CLIMB_MARGIN_M = 50.0

    /** bootstrap 初始化所需的最少样本数（issue #50）：前两个样本不上屏，凑齐 3 个再取中位数。 */
    const val BOOTSTRAP_SAMPLES = 3

    /** bootstrap 样本的允许离散度（米）：3 个样本两两差 ≤30m 才认为已稳定，可初始化。 */
    const val BOOTSTRAP_SPREAD_M = 30.0

    /**
     * 垂直精度闸门（issue #50）。
     *
     * - 无垂直精度信息（`hasVerticalAccuracy() == false`）：放行，不能因缺字段把有效定位全拦掉。
     * - `vAccMeters <= 0` 或 NaN：按「无效/未知」放行，不把 0 当成极高精度。
     * - 有精度且 > [MAX_VERTICAL_ACCURACY_M]：拒收。
     */
    fun acceptVerticalAccuracy(hasVerticalAccuracy: Boolean, vAccMeters: Float): Boolean {
        if (!hasVerticalAccuracy) return true
        if (vAccMeters <= 0f || vAccMeters.isNaN()) return true
        return vAccMeters <= MAX_VERTICAL_ACCURACY_M
    }

    /**
     * 跳变闸门（issue #50）：判断两个海拔样本之间的变化是否可能由真实运动产生。
     *
     * 允许的最大变化量为 `MAX_CLIMB_RATE_MPS * Δt秒 + CLIMB_MARGIN_M`。例如静止 40m 的定位
     * 在 180 秒后突然报 -3025m（Δh≈3065m，允许仅 950m）应判为物理不可能并拒收。
     *
     * `Δt <= 0`（时间戳相同、时钟回拨或负值）按 1 秒处理，避免除零并保留 [CLIMB_MARGIN_M] 的容差。
     * `prev` 不存在时不调用本函数，由调用方走 bootstrap 初始化。
     */
    fun isJumpPlausible(
        prevMeters: Double,
        prevElapsedNanos: Long,
        sampleMeters: Double,
        sampleElapsedNanos: Long
    ): Boolean {
        val dtNanos = sampleElapsedNanos - prevElapsedNanos
        val dtSeconds = if (dtNanos <= 0L) 1.0 else dtNanos / 1_000_000_000.0
        val allowed = MAX_CLIMB_RATE_MPS * dtSeconds + CLIMB_MARGIN_M
        return abs(sampleMeters - prevMeters) <= allowed
    }

    /** 三个样本的中位数（issue #50 bootstrap 初始化用），对单点异常不敏感。 */
    fun medianOfThree(a: Double, b: Double, c: Double): Double =
        maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    /**
     * bootstrap 是否已可初始化（issue #50）：样本数 ≥ [BOOTSTRAP_SAMPLES]，
     * 且全部样本的极差 ≤ [BOOTSTRAP_SPREAD_M]（等价于两两差 ≤30m）。
     *
     * 纯函数、不修改入参；调用方负责维护滑动窗口。
     */
    fun bootstrapReady(samples: List<Double>): Boolean {
        if (samples.size < BOOTSTRAP_SAMPLES) return false
        val min = samples.minOrNull() ?: return false
        val max = samples.maxOrNull() ?: return false
        return max - min <= BOOTSTRAP_SPREAD_M
    }

    /**
     * 判定一个定位是否可用于海拔显示。
     *
     * @param altitude 海拔米数；`null` 表示该定位 `hasAltitude() == false`（无卫星高度解算）。
     * @param ageMs 该定位距现在的毫秒数（由调用方算 `nowMs - location.time` 后传入）；
     *   `null` 表示调用方无法判定年龄，按过期处理（保守拒收）。
     * @return true 当且仅当海拔非 null 且年龄在 [MAX_LOCATION_AGE_MS] 内。
     */
    fun acceptLocation(altitude: Double?, ageMs: Long?): Boolean {
        if (altitude == null || ageMs == null) return false
        // ageMs 可能为负（时钟回拨或定位时间在未来），按 0 处理（视为「刚好现在」），不因负值拒收。
        val age = if (ageMs < 0) 0L else ageMs
        return age <= MAX_LOCATION_AGE_MS
    }

    /**
     * 指数滑动平均（EMA）。
     *
     * - `prev == null`（首个样本或失锁后首个恢复样本）：直接返回 [sample]，不做加权，避免从 0 拉起。
     * - 否则：`prev * (1 - alpha) + sample * alpha`。
     *
     * @param prev 上一次平滑后的值；`null` 表示无历史。
     * @param sample 本次原始海拔（米，WGS84 椭球高）。
     * @param alpha 新样本权重，越大越跟手、越小越平滑；默认 [DEFAULT_EMA_ALPHA]。
     */
    fun emaSmooth(prev: Double?, sample: Double, alpha: Float = DEFAULT_EMA_ALPHA): Double {
        if (prev == null) return sample
        val a = alpha.coerceIn(0f, 1f)
        return prev * (1.0 - a) + sample * a
    }

    /**
     * 把平滑后的海拔渲染成整数米字符串。
     *
     * - `null` → `—`（无有效数据，issue #40 验收标准 2）。
     * - 否则四舍五入到整数米：`1234.5` → `1235`，`-12.4` → `-12`，`0` → `0`。
     * - 负海拔（低于海平面）允许，渲染为 `-12m`。
     *
     * 四舍五入用 [roundToInt]：`0.5` 进位（Kotlin `roundToInt` 对 .5 取偶数银行舍入，
     * 但海拔 ±0.5m 的差异在 GPS ±10–20m 精度下无意义，不做特殊处理）。
     */
    fun formatAltitude(meters: Double?): String {
        if (meters == null) return "—"
        val rounded = meters.roundToInt()
        return "${rounded}m"
    }

    /**
     * 便捷重载：直接接收已取整的海拔（null → `—`）。供 UI 层缓存了整数海拔时使用。
     */
    fun formatAltitude(meters: Int?): String =
        if (meters == null) "—" else "${meters}m"
}
