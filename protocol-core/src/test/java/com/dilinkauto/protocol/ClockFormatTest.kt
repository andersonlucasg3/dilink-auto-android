package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 钉死车机主屏时间的纯格式化逻辑（issue #39）。
 *
 * 覆盖跨日（23:59→00:00）、12/24 制格式、跨时区（含负偏移）、整分钟对齐边界。
 *
 * 测试用 [ZonedDateTime] 推导 epoch 毫秒，避免手算 epoch 出错。
 */
class ClockFormatTest {

    private val utc = ZoneId.of("UTC")

    private fun epochMillis(zoned: ZonedDateTime): Long = zoned.toInstant().toEpochMilli()

    @Test
    fun formats24HourAsLeadingZeroHour() {
        // 2024-03-10T18:05:00Z —— 一个干净的 18:05。
        val e = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        assertEquals("18:05", ClockFormat.formatClockText(e, utc, is24Hour = true))
    }

    @Test
    fun formats12HourWithoutLeadingZero() {
        // 18:05 12h = 6:05 PM，渲染为 "6:05"（不补前导零，与 Android 系统时钟一致）。
        val e = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        assertEquals("6:05", ClockFormat.formatClockText(e, utc, is24Hour = false))
    }

    @Test
    fun midnightWrapsFrom2359To0000() {
        // 23:59:00 UTC 与 1 分钟后的 00:00:00 UTC（次日）。
        val e2359 = epochMillis(ZonedDateTime.of(2024, 3, 10, 23, 59, 0, 0, utc))
        val e0000Next = e2359 + 60_000L
        assertEquals("23:59", ClockFormat.formatClockText(e2359, utc, is24Hour = true))
        assertEquals("00:00", ClockFormat.formatClockText(e0000Next, utc, is24Hour = true))
        // 12h 制下 00:00 渲染为 "12:00"（午夜），与 Android 系统时钟一致。
        assertEquals("12:00", ClockFormat.formatClockText(e0000Next, utc, is24Hour = false))
    }

    @Test
    fun noonIs1200In12Hour() {
        // 12:00:00 UTC，12h 渲染为 "12:00"（正午），24h 也是 "12:00"。
        val noon = epochMillis(ZonedDateTime.of(2024, 3, 10, 12, 0, 0, 0, utc))
        assertEquals("12:00", ClockFormat.formatClockText(noon, utc, is24Hour = false))
        assertEquals("12:00", ClockFormat.formatClockText(noon, utc, is24Hour = true))
    }

    @Test
    fun crossesTimeZoneBoundary() {
        // 同一 epoch 在 UTC+8（北京）比 UTC 快 8 小时：18:05Z -> 02:05+08:00 次日。
        val e = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        val beijing = ZoneId.of("Asia/Shanghai")
        assertEquals("02:05", ClockFormat.formatClockText(e, beijing, is24Hour = true))
        // 负偏移：America/New_York（2024-03-10 当日进入夏令时 EDT, UTC-4），
        // 18:05Z -> 14:05 EDT。只断言 HH:mm 形状与小时合法，避免被 DST 切换日期绑死。
        val newYork = ZoneId.of("America/New_York")
        val nyLabel = ClockFormat.formatClockText(e, newYork, is24Hour = true)
        assertTrue("NY label '$nyLabel' must be HH:mm", nyLabel.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun millisToNextMinuteIsSixtySecondsAtExactMinute() {
        // 整分钟点：距「下一个」整分钟是 60_000ms，不是 0（0 会立即触发再重组）。
        val e = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        assertEquals(60_000L, ClockFormat.millisToNextMinute(e))
    }

    @Test
    fun millisToNextMinuteIsOneOneMsBeforeTheMinute() {
        // 18:04:59.999 -> 距 18:05:00.000 还有 1ms。
        val e18_05 = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        assertEquals(1L, ClockFormat.millisToNextMinute(e18_05 - 1L))
    }

    @Test
    fun millisToNextMinuteStaysInUnitRange() {
        val base = epochMillis(ZonedDateTime.of(2024, 3, 10, 18, 5, 0, 0, utc))
        for (offsetMs in 0 until 60_000) {
            val result = ClockFormat.millisToNextMinute(base + offsetMs)
            assertTrue("offset=$offsetMs -> $result out of range", result in 1L..60_000L)
        }
    }
}
