package com.dilinkauto.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [Letterbox] 的纯几何单测：绘制与触摸换算共用同一算法，这里锁死它的行为。 */
class LetterboxTest {

    @Test
    fun wideContainer_letterboxesTopAndBottom() {
        // 容器 1000x1000，内容 16:9 → 内容缩放后 1000x562，上下各留 219
        val f = Letterbox.fit(1000, 1000, 1920, 1080)
        assertEquals(1000, f.width)
        assertEquals(562, f.height)
        assertEquals(0, f.x)
        assertEquals(219, f.y)
    }

    @Test
    fun tallContainer_pillarboxesLeftAndRight() {
        // 容器 1000x1000，内容 9:16 → 内容缩放后 562x1000，左右各留 219
        val f = Letterbox.fit(1000, 1000, 1080, 1920)
        assertEquals(562, f.width)
        assertEquals(1000, f.height)
        assertEquals(219, f.x)
        assertEquals(0, f.y)
    }

    @Test
    fun sameAspect_fillsContainerExactly() {
        val f = Letterbox.fit(1280, 720, 1920, 1080)
        assertEquals(Letterbox.Fitted(0, 0, 1280, 720), f)
    }

    @Test
    fun toNormalized_mapsContentCorners() {
        val f = Letterbox.Fitted(x = 100, y = 50, width = 800, height = 600)
        assertEquals(Letterbox.Normalized(0f, 0f), Letterbox.toNormalized(100, 50, f))
        assertEquals(Letterbox.Normalized(0.5f, 0.5f), Letterbox.toNormalized(500, 350, f))
        // 像素区间是 [x, x+width)，最右下的可用像素是 899/649 → 归一化后略小于 1；
        // 恰好能到 1.0 的是 clamped 版本（见 toNormalizedClamped_convergesToEdges）。
        val bottomRight = requireNotNull(Letterbox.toNormalized(899, 649, f))
        assertEquals(0.99875f, bottomRight.x, 0.0001f)
        assertEquals(0.99833f, bottomRight.y, 0.0001f)
    }

    @Test
    fun toNormalized_rejectsLetterboxBars() {
        val f = Letterbox.Fitted(x = 100, y = 50, width = 800, height = 600)
        assertNull(Letterbox.toNormalized(99, 350, f))   // 左侧黑边
        assertNull(Letterbox.toNormalized(900, 350, f))  // 右侧黑边
        assertNull(Letterbox.toNormalized(500, 49, f))   // 上方黑边
        assertNull(Letterbox.toNormalized(500, 650, f))  // 下方黑边
    }

    @Test
    fun toNormalizedClamped_convergesToEdges() {
        val f = Letterbox.Fitted(x = 100, y = 50, width = 800, height = 600)
        assertEquals(Letterbox.Normalized(0f, 0f), Letterbox.toNormalizedClamped(-500, -500, f))
        assertEquals(Letterbox.Normalized(1f, 1f), Letterbox.toNormalizedClamped(5000, 5000, f))
        // 内容区内与 toNormalized 保持一致
        assertEquals(Letterbox.Normalized(0.5f, 0.5f), Letterbox.toNormalizedClamped(500, 350, f))
    }

    @Test
    fun degenerateSizes_fallBackToUnitRect() {
        assertEquals(Letterbox.Fitted(0, 0, 1, 1), Letterbox.fit(0, 100, 1920, 1080))
        assertEquals(Letterbox.Fitted(0, 0, 1, 1), Letterbox.fit(100, 100, 0, 0))
    }
}
