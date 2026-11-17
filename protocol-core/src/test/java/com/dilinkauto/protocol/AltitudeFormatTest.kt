package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死车机主屏海拔的纯判定 / 平滑 / 格式化逻辑（issue #40）。
 *
 * 覆盖过期定位拒收、EMA 收敛与首样本直采、负海拔、null → `—`、取整边界。
 */
class AltitudeFormatTest {

    // ─── acceptLocation ───

    @Test
    fun rejectsNullAltitude() {
        assertFalse(AltitudeFormat.acceptLocation(null, ageMs = 0L))
    }

    @Test
    fun rejectsNullAge() {
        // 调用方无法判定年龄（例如 time 字段缺失）→ 保守拒收。
        assertFalse(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = null))
    }

    @Test
    fun acceptsFreshLocation() {
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 5_000L))
    }

    @Test
    fun rejectsStaleLocation() {
        // 11s > 10s 上限 → 过期拒收（地库失锁后缓存定位冒充实时）。
        assertFalse(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 11_000L))
    }

    @Test
    fun acceptsLocationAtExactAgeBoundary() {
        // 10s 恰好等于上限 → 仍可用（含边界）。
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 10_000L))
    }

    @Test
    fun negativeAgeTreatedAsZero() {
        // 时钟回拨导致 ageMs 为负 → 视为「刚好现在」，不拒收。
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = -3_000L))
    }

    // ─── emaSmooth ───

    @Test
    fun firstSampleAdoptedDirectly() {
        // 无历史时首样本直采，不从 0 拉起。
        assertEquals(123.0, AltitudeFormat.emaSmooth(prev = null, sample = 123.0), 0.001)
    }

    @Test
    fun emaConvergesTowardNewSamples() {
        // prev=100, sample=200, alpha=0.2 -> 100*0.8 + 200*0.2 = 120
        assertEquals(120.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 0.2f), 0.001)
    }

    @Test
    fun emaWithAlphaOneFollowsSampleExactly() {
        assertEquals(200.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 1f), 0.001)
    }

    @Test
    fun emaClampsAlphaAboveOne() {
        // alpha > 1 被钳到 1，等价于完全跟随新样本。
        assertEquals(200.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 5f), 0.001)
    }

    // ─── acceptVerticalAccuracy（issue #50）───

    @Test
    fun rejectsPoorVerticalAccuracyOver100m() {
        // 垂直精度 101m > 上限 100m → 拒收。
        assertFalse(AltitudeFormat.acceptVerticalAccuracy(hasVerticalAccuracy = true, vAccMeters = 101f))
    }

    @Test
    fun acceptsGoodVerticalAccuracy() {
        assertTrue(AltitudeFormat.acceptVerticalAccuracy(hasVerticalAccuracy = true, vAccMeters = 20f))
    }

    @Test
    fun acceptsWhenVerticalAccuracyMissing() {
        // 无精度信息时不能把所有有效定位都拦掉。
        assertTrue(AltitudeFormat.acceptVerticalAccuracy(hasVerticalAccuracy = false, vAccMeters = 500f))
    }

    @Test
    fun nonPositiveVerticalAccuracyTreatedAsUnknown() {
        // 0 / 负值视为「无效或未知」，放行，不当作极高精度。
        assertTrue(AltitudeFormat.acceptVerticalAccuracy(hasVerticalAccuracy = true, vAccMeters = 0f))
        assertTrue(AltitudeFormat.acceptVerticalAccuracy(hasVerticalAccuracy = true, vAccMeters = -1f))
    }

    // ─── isJumpPlausible（issue #50）───

    private val oneSecondNanos = 1_000_000_000L

    @Test
    fun rejectsPhysicallyImpossibleJump() {
        // 静止 40m，10 秒后报 500m：Δh=460m，允许 5*10+50=100m → 拒收。
        assertFalse(
            AltitudeFormat.isJumpPlausible(
                prevMeters = 40.0,
                prevElapsedNanos = 0L,
                sampleMeters = 500.0,
                sampleElapsedNanos = 10L * oneSecondNanos
            )
        )
    }

    @Test
    fun acceptsRealisticClimb() {
        // 10 秒爬升 40m（4m/s），在 5m/s + 50m 余量内 → 放行。
        assertTrue(
            AltitudeFormat.isJumpPlausible(
                prevMeters = 40.0,
                prevElapsedNanos = 0L,
                sampleMeters = 80.0,
                sampleElapsedNanos = 10L * oneSecondNanos
            )
        )
    }

    @Test
    fun rejectsThreeMinuteImpossibleDrop() {
        // 静止 40m，180 秒后单点 -3025m：Δh=3065m，允许 5*180+50=950m → 拒收。
        assertFalse(
            AltitudeFormat.isJumpPlausible(
                prevMeters = 40.0,
                prevElapsedNanos = 0L,
                sampleMeters = -3025.0,
                sampleElapsedNanos = 180L * oneSecondNanos
            )
        )
    }

    @Test
    fun nonPositiveDeltaTimeTreatedAsOneSecond() {
        // Δt<=0 按 1 秒：允许 5+50=55m。
        assertTrue(AltitudeFormat.isJumpPlausible(40.0, 0L, 80.0, 0L))
        assertFalse(AltitudeFormat.isJumpPlausible(40.0, 0L, 200.0, 0L))
    }

    // ─── bootstrap 判定与中位数（issue #50）───

    @Test
    fun bootstrapNeedsAtLeastThreeSamples() {
        // 前 2 个样本不初始化。
        assertFalse(AltitudeFormat.bootstrapReady(listOf(40.0, 41.0)))
        assertFalse(AltitudeFormat.bootstrapReady(emptyList()))
    }

    @Test
    fun bootstrapRejectsWideSpread() {
        // 40 / 41 / 80：极差 40m > 30m → 不初始化。
        assertFalse(AltitudeFormat.bootstrapReady(listOf(40.0, 41.0, 80.0)))
    }

    @Test
    fun bootstrapReadyWithThreeStableSamples() {
        // 第 3 个样本加入后两两差 ≤30m → 可初始化。
        assertTrue(AltitudeFormat.bootstrapReady(listOf(40.0, 41.0, 39.0)))
    }

    @Test
    fun bootstrapAcceptsSpreadAtBoundary() {
        // 极差恰好 30m → 仍可初始化（含边界）。
        assertTrue(AltitudeFormat.bootstrapReady(listOf(40.0, 70.0, 55.0)))
    }

    @Test
    fun medianOfThreeReturnsMiddleValue() {
        assertEquals(2.0, AltitudeFormat.medianOfThree(3.0, 1.0, 2.0), 0.001)
        assertEquals(40.0, AltitudeFormat.medianOfThree(41.0, 40.0, 39.0), 0.001)
    }

    @Test
    fun medianOfThreeIgnoresSingleOutlier() {
        // 单点异常 -3025m 不影响中位数。
        assertEquals(40.0, AltitudeFormat.medianOfThree(-3025.0, 40.0, 41.0), 0.001)
    }

    @Test
    fun singleImpossibleSpikeKeepsOutputNearBaseline() {
        // bootstrap：3 个近 40m 样本 → 中位数 40m 初始化。
        val samples = listOf(41.0, 40.0, 39.0)
        assertTrue(AltitudeFormat.bootstrapReady(samples))
        var smoothed = AltitudeFormat.medianOfThree(samples[0], samples[1], samples[2])
        assertEquals(40.0, smoothed, 0.001)

        // 静止 3 分钟后单点 -3025m：跳变闸门拒收，不进入 EMA，输出保持 40m。
        assertFalse(
            AltitudeFormat.isJumpPlausible(
                prevMeters = 41.0,
                prevElapsedNanos = 0L,
                sampleMeters = -3025.0,
                sampleElapsedNanos = 180L * oneSecondNanos
            )
        )
        assertEquals("40m", AltitudeFormat.formatAltitude(smoothed))

        // 下一秒正常样本 40.5m 被采纳：EMA(40, 40.5) = 40.1 → 仍渲染 40m。
        smoothed = AltitudeFormat.emaSmooth(smoothed, 40.5)
        assertEquals("40m", AltitudeFormat.formatAltitude(smoothed))
    }

    // ─── formatAltitude ───

    @Test
    fun nullRendersAsDash() {
        assertEquals("—", AltitudeFormat.formatAltitude(meters = null as Double?))
        assertEquals("—", AltitudeFormat.formatAltitude(meters = null as Int?))
    }

    @Test
    fun positiveAltitudeRoundsToInt() {
        // 1234.5 -> 1235（四舍五入）。
        assertEquals("1235m", AltitudeFormat.formatAltitude(1234.5))
    }

    @Test
    fun negativeAltitudeRoundsTowardZeroForFraction() {
        // -12.4 -> -12（四舍五入到最接近的整数）。
        assertEquals("-12m", AltitudeFormat.formatAltitude(-12.4))
    }

    @Test
    fun negativeAltitudeWithLargeFractionRoundsDown() {
        // -12.6 -> -13。
        assertEquals("-13m", AltitudeFormat.formatAltitude(-12.6))
    }

    @Test
    fun seaLevelRendersAsZero() {
        assertEquals("0m", AltitudeFormat.formatAltitude(0.0))
        assertEquals("0m", AltitudeFormat.formatAltitude(0))
    }

    @Test
    fun intOverloadMatchesDoubleOverload() {
        // 整数重载与 Double 重载（已取整）输出一致。
        assertEquals(AltitudeFormat.formatAltitude(1235.0), AltitudeFormat.formatAltitude(1235))
    }
}
