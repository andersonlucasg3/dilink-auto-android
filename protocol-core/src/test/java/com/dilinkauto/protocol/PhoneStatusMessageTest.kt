package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 手机电量消息的编解码（issue #151，[DataMsg.PHONE_STATUS]）。
 *
 * 定长 3 字节：level | flags(bit0=charging) | temperature。这里钉住：
 * - 正常值往返（含充电位与温度）；
 * - 边界值 0% / 100%；
 * - flags 未知位被忽略（向前兼容），只在 bit0 上取充电；
 * - 载荷过短 / 过长 / 空 / level 越界 → [ProtocolDecodeException]（不是
 *   BufferUnderflow / NPE 之类的未受检异常，否则会打断车机的 data reader）；
 * - 编码侧越界 → IllegalArgumentException，不把坏值发上线。
 */
class PhoneStatusMessageTest {

    @Test
    fun `往返编码解码保留 level charging 与温度`() {
        val original = PhoneStatus(level = 86, charging = true, temperatureC = 31)
        val decoded = PhoneStatus.decode(original.encode())
        assertEquals(86, decoded.level)
        assertTrue(decoded.charging)
        assertEquals(31, decoded.temperatureC)
    }

    @Test
    fun `未充电且温度未知时解码为 null 温度`() {
        val decoded = PhoneStatus.decode(PhoneStatus(level = 42, charging = false).encode())
        assertEquals(42, decoded.level)
        assertFalse(decoded.charging)
        assertNull(decoded.temperatureC)
    }

    @Test
    fun `边界值 0 与 100 可以往返`() {
        assertEquals(0, PhoneStatus.decode(PhoneStatus(0, false).encode()).level)
        assertEquals(100, PhoneStatus.decode(PhoneStatus(100, true).encode()).level)
    }

    @Test
    fun `负温度按有符号字节往返`() {
        val decoded = PhoneStatus.decode(PhoneStatus(level = 55, charging = false, temperatureC = -12).encode())
        assertEquals(-12, decoded.temperatureC)
    }

    @Test
    fun `flags 只有 bit0 表示充电 其余位被忽略`() {
        // bit0=1（充电）+ bit1/bit7 这些保留位置 1：解码只认 bit0。
        val payload = byteArrayOf(86.toByte(), 0xFF.toByte(), PhoneStatus.TEMPERATURE_UNKNOWN.toByte())
        val decoded = PhoneStatus.decode(payload)
        assertTrue(decoded.charging)

        // bit0=0、其余位全 1：仍然是未充电。
        val notCharging = byteArrayOf(86.toByte(), 0xFE.toByte(), PhoneStatus.TEMPERATURE_UNKNOWN.toByte())
        assertFalse(PhoneStatus.decode(notCharging).charging)
    }

    @Test
    fun `载荷过短抛 ProtocolDecodeException`() {
        assertDecodeFails(byteArrayOf(86.toByte(), 0x01))
        assertDecodeFails(ByteArray(0))
    }

    @Test
    fun `载荷过长抛 ProtocolDecodeException`() {
        assertDecodeFails(byteArrayOf(86.toByte(), 0x01, 30, 0))
    }

    @Test
    fun `level 大于 100 抛 ProtocolDecodeException`() {
        assertDecodeFails(byteArrayOf(101.toByte(), 0x00, PhoneStatus.TEMPERATURE_UNKNOWN.toByte()))
    }

    @Test
    fun `编码越界值抛 IllegalArgumentException`() {
        listOf(-1, 101, 255).forEach { bad ->
            try {
                PhoneStatus(bad, false).encode()
                fail("level=$bad 应当被编码侧拒绝")
            } catch (_: IllegalArgumentException) {
                // 预期：坏值不上线
            }
        }
        try {
            PhoneStatus(50, false, PhoneStatus.TEMPERATURE_UNKNOWN).encode()
            fail("温度哨兵值不应被当作真实温度编码")
        } catch (_: IllegalArgumentException) {
            // 预期：未知温度必须用 null 表达
        }
    }

    private fun assertDecodeFails(payload: ByteArray) {
        try {
            PhoneStatus.decode(payload)
            fail("payload ${payload.size}B 应当解码失败")
        } catch (_: ProtocolDecodeException) {
            // 预期：明确的解码异常，供 handleDataFrame 捕获并记日志
        }
    }
}
