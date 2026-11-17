package com.dilinkauto.desktop

import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DesktopConfig 默认值单测：这一文件的唯一逻辑就是默认值，且必须来自协议常量。
 */
class DesktopConfigTest {

    @Test
    fun `defaults match protocol constants`() {
        val c = DesktopConfig(phoneHost = "1.2.3.4", viewportWidth = 1280, viewportHeight = 720)
        assertEquals(Ports.DEFAULT_PORT, c.controlPort)
        assertEquals(Ports.VIDEO_PORT, c.videoPort)
        assertEquals(Ports.INPUT_PORT, c.inputPort)
        assertEquals(160, c.screenDpi)
        assertEquals(VideoConfig.TARGET_FPS, c.targetFps)
        assertEquals(VideoConfig.DEFAULT_BITRATE, c.bitrate)
        assertEquals(0, c.dpiOverride)
        assertEquals(1, c.appVersionCode)
        assertEquals("DiLink-Desktop", c.deviceName)
        assertEquals(10_000L, c.connectTimeoutMs)
    }

    @Test
    fun `copy changes only the named field`() {
        val c = DesktopConfig(phoneHost = "1.2.3.4", viewportWidth = 1280, viewportHeight = 720)
        val d = c.copy(dpiOverride = 240)
        assertEquals(240, d.dpiOverride)
        // 其余字段保持不变（DesktopApp.restart 正是这么改 DPI 的）
        assertEquals(c.phoneHost, d.phoneHost)
        assertEquals(c.deviceName, d.deviceName)
        assertEquals(c.targetFps, d.targetFps)
        assertEquals(c.bitrate, d.bitrate)
        assertEquals(c.viewportWidth, d.viewportWidth)
    }
}
