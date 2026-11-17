package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VideoConfig.calculateOptimalDpi 单测：竖屏 app 在横屏车机上的 DPI 校准决定可读性与可点击性，
 * 每条分支（退避/横屏金线/竖屏/上报值带内钳制/带外/下限兜底）都是独立的 UX 契约。
 */
class VideoConfigTest {

    @Test
    fun `frame interval is integer derived`() {
        // 锁定 1000/20 = 50ms（issue #71 起 24 → 20 fps）；
        // 改 TARGET_FPS 会连带改掉所有管线超时的推导，必须显性失败
        assertEquals(50L, VideoConfig.FRAME_INTERVAL_MS)
    }

    @Test
    fun `calculate dpi non positive dims fall back to default`() {
        assertEquals(160, VideoConfig.calculateOptimalDpi(0, 0, 0))
        assertEquals(160, VideoConfig.calculateOptimalDpi(-1, 100, 0))
        assertEquals(160, VideoConfig.calculateOptimalDpi(100, -1, 0))
    }

    @Test
    fun `calculate dpi landscape height band uses golden formula`() {
        // (1920,1080)：maxSafe=240、golden=(1080*0.52*160/385)=233、carReported=0 → 233
        assertEquals(233, VideoConfig.calculateOptimalDpi(1920, 1080, 0))
    }

    @Test
    fun `calculate dpi portrait branch`() {
        // (892,2200)：maxSafe=375、golden=(892*160/385)=370 → 370
        assertEquals(370, VideoConfig.calculateOptimalDpi(892, 2200, 0))
    }

    @Test
    fun `calculate dpi car reported inside band is capped to max safe`() {
        // (1920,720,320)：maxSafe=160，min(320,160)=160
        assertEquals(160, VideoConfig.calculateOptimalDpi(1920, 720, 320))
        // (1920,1080,200)：min(200,240)=200
        assertEquals(200, VideoConfig.calculateOptimalDpi(1920, 1080, 200))
    }

    @Test
    fun `calculate dpi car reported outside band uses golden`() {
        // 119 与 321 都落在 [120,320] 之外 → 走 golden=233，而不是上报值
        assertEquals(233, VideoConfig.calculateOptimalDpi(1920, 1080, 119))
        assertEquals(233, VideoConfig.calculateOptimalDpi(1920, 1080, 321))
    }

    @Test
    fun `calculate dpi never below 120 when max safe is tiny`() {
        // (200,100)：maxSafe=22、golden=21 → coerceIn(120, max(120,22)=120) = 120
        assertEquals(120, VideoConfig.calculateOptimalDpi(200, 100, 0))
    }
}
