package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 信息栏电量文本（issue #151，[BatteryFormat]）。
 *
 * 约束来自横屏左栏 76dp 定宽：文本只能是「数字 + %」，最多再加一个充电后缀 `+`，
 * 不加图标、不加进度条。这里钉住三态（无数据 / 未充电 / 充电）与边界值。
 */
class BatteryFormatTest {

    @Test
    fun `无数据渲染破折号`() {
        assertEquals("—", BatteryFormat.formatBattery(level = null, charging = null))
        assertEquals("—", BatteryFormat.formatBattery(level = null, charging = true))
    }

    @Test
    fun `未充电只显示百分比`() {
        assertEquals("86%", BatteryFormat.formatBattery(level = 86, charging = false))
    }

    @Test
    fun `充电中加加号后缀`() {
        assertEquals("86%+", BatteryFormat.formatBattery(level = 86, charging = true))
    }

    @Test
    fun `charging 未知按未充电渲染`() {
        // 老手机不带 charging 位时解码为 false；显式 null 也不该显示成充电中。
        assertEquals("86%", BatteryFormat.formatBattery(level = 86, charging = null))
    }

    @Test
    fun `边界值 0 与 100`() {
        assertEquals("0%", BatteryFormat.formatBattery(level = 0, charging = false))
        assertEquals("100%", BatteryFormat.formatBattery(level = 100, charging = false))
        assertEquals("0%+", BatteryFormat.formatBattery(level = 0, charging = true))
        assertEquals("100%+", BatteryFormat.formatBattery(level = 100, charging = true))
    }

    @Test
    fun `越界值夹到 0-100 不产生空文本`() {
        assertEquals("0%", BatteryFormat.formatBattery(level = -5, charging = false))
        assertEquals("100%", BatteryFormat.formatBattery(level = 255, charging = false))
    }
}
