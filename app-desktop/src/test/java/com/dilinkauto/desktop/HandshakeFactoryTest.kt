package com.dilinkauto.desktop

import com.dilinkauto.protocol.PROTOCOL_VERSION
import com.dilinkauto.protocol.VdAreaTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HandshakeFactory.build / evenAlign 单测：桌面端把配置映射成 HandshakeRequest。
 * evenAlign 用 evenMin2（下限 2），不是 DimAlign.even（对负值会给天文数字）。
 * dpiOverride 此前只被间接覆盖，这里直接锁字段映射。
 */
class HandshakeFactoryTest {

    @Test
    fun `build maps every config field onto the request`() {
        val cfg = DesktopConfig(
            phoneHost = "10.0.0.5",
            viewportWidth = 1281, viewportHeight = 721,
            screenDpi = 213, targetFps = 30, bitrate = 6_000_000,
            dpiOverride = 240, appVersionCode = 7, deviceName = "MyDesk",
            vdAreaTier = VdAreaTier.COMPAT_2560
        )
        val req = HandshakeFactory.build(cfg)
        assertEquals("MyDesk", req.deviceName)
        assertEquals(1280, req.screenWidth)   // evenAlign(1281)
        assertEquals(720, req.screenHeight)    // evenAlign(721)
        assertEquals(213, req.screenDpi)
        assertEquals(30, req.targetFps)
        assertEquals(6_000_000, req.bitrate)
        assertEquals(240, req.dpiOverride)
        assertEquals(7, req.appVersionCode)
        assertEquals(PROTOCOL_VERSION, req.protocolVersion)
        assertEquals(VdAreaTier.COMPAT_2560, req.vdAreaTier)
    }

    @Test
    fun `build defaults the VD area tier to AUTO`() {
        val cfg = DesktopConfig(phoneHost = "1.2.3.4", viewportWidth = 1280, viewportHeight = 720)
        assertEquals(VdAreaTier.AUTO, HandshakeFactory.build(cfg).vdAreaTier)
    }

    @Test
    fun `build defaults dpiOverride to zero`() {
        val cfg = DesktopConfig(phoneHost = "1.2.3.4", viewportWidth = 1280, viewportHeight = 720)
        // 0 = 手机自动标定；回归成 160 会覆盖每台手机的校准
        assertEquals(0, HandshakeFactory.build(cfg).dpiOverride)
    }

    @Test
    fun `even align floors viewport to two including tiny values`() {
        assertEquals(2, HandshakeFactory.evenAlign(3))
        assertEquals(2, HandshakeFactory.evenAlign(1))
        assertEquals(1920, HandshakeFactory.evenAlign(1920))
    }

    @Test
    fun `even align does not blow up negatives`() {
        // 若误用 DimAlign.even，-5 会得到 around 2.1e9；evenMin2 兜底为 2
        assertEquals(2, HandshakeFactory.evenAlign(-5))
        assertTrue(HandshakeFactory.evenAlign(-1) < 100)
    }
}
