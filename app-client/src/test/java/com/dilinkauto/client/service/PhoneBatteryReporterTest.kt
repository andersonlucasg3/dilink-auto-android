package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量广播字段的纯换算（issue #151，[parseBatterySample]）。
 *
 * 这些字段没有统一的跨机型保证：`EXTRA_LEVEL` 是相对 `EXTRA_SCALE` 的刻度、
 * `EXTRA_TEMPERATURE` 单位是 0.1°C、`EXTRA_STATUS` 有 5 个取值。用固定数值
 * 钉住换算规则，避免真机上「50/255 报成 50%」这类错误只能靠肉眼发现。
 */
class PhoneBatteryReporterTest {

    // Android BatteryManager 的 BATTERY_STATUS_* 取值（避免单测引入 Android 类）
    private val statusCharging = 2
    private val statusDischarging = 3
    private val statusNotCharging = 4
    private val statusFull = 5

    @Test
    fun `level 与 scale 换算成百分比`() {
        assertEquals(86, parseBatterySample(86, 100, statusDischarging, Int.MIN_VALUE)?.level)
        // 50/255 不能当 50% 处理，实际是 20%
        assertEquals(20, parseBatterySample(50, 255, statusDischarging, Int.MIN_VALUE)?.level)
    }

    @Test
    fun `缺失或非法 level scale 返回 null`() {
        assertNull(parseBatterySample(-1, 100, statusDischarging, Int.MIN_VALUE))
        assertNull(parseBatterySample(86, 0, statusDischarging, Int.MIN_VALUE))
        assertNull(parseBatterySample(86, -1, statusDischarging, Int.MIN_VALUE))
    }

    @Test
    fun `换算结果夹在 0 到 100`() {
        assertEquals(100, parseBatterySample(120, 100, statusDischarging, Int.MIN_VALUE)?.level)
        assertEquals(0, parseBatterySample(0, 100, statusDischarging, Int.MIN_VALUE)?.level)
    }

    @Test
    fun `CHARGING 与 FULL 都算充电中`() {
        assertTrue(parseBatterySample(50, 100, statusCharging, Int.MIN_VALUE)!!.charging)
        assertTrue(parseBatterySample(100, 100, statusFull, Int.MIN_VALUE)!!.charging)
        assertFalse(parseBatterySample(50, 100, statusDischarging, Int.MIN_VALUE)!!.charging)
        assertFalse(parseBatterySample(50, 100, statusNotCharging, Int.MIN_VALUE)!!.charging)
    }

    @Test
    fun `温度按 0_1 摄氏度换算并四舍五入`() {
        assertEquals(32, parseBatterySample(50, 100, statusDischarging, 315)?.temperatureC)
        assertEquals(-5, parseBatterySample(50, 100, statusDischarging, -50)?.temperatureC)
        assertEquals(0, parseBatterySample(50, 100, statusDischarging, 0)?.temperatureC)
    }

    @Test
    fun `缺失或越界温度上报为 null`() {
        assertNull(parseBatterySample(50, 100, statusDischarging, Int.MIN_VALUE)?.temperatureC)
        assertNull(parseBatterySample(50, 100, statusDischarging, 2_000)?.temperatureC)
        assertNull(parseBatterySample(50, 100, statusDischarging, -2_000)?.temperatureC)
    }
}
