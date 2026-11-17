package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CaptureModePolicy 单测：VD 优先 + 逐级降级（息屏投屏 → 主屏投屏）契约。
 */
class CaptureModePolicyTest {

    @Test
    fun `prefers virtual display when the device can light it`() {
        assertEquals(ScreenMode.VIRTUAL_DISPLAY, CaptureModePolicy.preferred(vdLightingSupported = true))
    }

    @Test
    fun `prefers screen off projection when the device cannot light a virtual display`() {
        assertEquals(ScreenMode.PROJECTION_SCREEN_OFF, CaptureModePolicy.preferred(vdLightingSupported = false))
    }

    @Test
    fun `fallback ladder steps one level at a time`() {
        assertEquals(ScreenMode.PROJECTION_SCREEN_OFF, CaptureModePolicy.fallbackFrom(ScreenMode.VIRTUAL_DISPLAY))
        assertEquals(ScreenMode.PROJECTION_MAIN, CaptureModePolicy.fallbackFrom(ScreenMode.PROJECTION_SCREEN_OFF))
        assertNull("主屏投屏是最后手段，无更低级", CaptureModePolicy.fallbackFrom(ScreenMode.PROJECTION_MAIN))
    }

    @Test
    fun `full ladder from vd visits all three in order`() {
        assertEquals(
            listOf(ScreenMode.VIRTUAL_DISPLAY, ScreenMode.PROJECTION_SCREEN_OFF, ScreenMode.PROJECTION_MAIN),
            CaptureModePolicy.ladder(ScreenMode.VIRTUAL_DISPLAY)
        )
    }

    @Test
    fun `ladder from screen off skips vd`() {
        assertEquals(
            listOf(ScreenMode.PROJECTION_SCREEN_OFF, ScreenMode.PROJECTION_MAIN),
            CaptureModePolicy.ladder(ScreenMode.PROJECTION_SCREEN_OFF)
        )
    }

    @Test
    fun `ladder from main is terminal`() {
        assertEquals(listOf(ScreenMode.PROJECTION_MAIN), CaptureModePolicy.ladder(ScreenMode.PROJECTION_MAIN))
        assertTrue(CaptureModePolicy.fallbackFrom(ScreenMode.PROJECTION_MAIN) == null)
    }
}
