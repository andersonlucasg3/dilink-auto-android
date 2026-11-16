package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 B：VD 复用判定（纯函数）。
 *
 * 规则：本进程已有 VD 且 display 仍存活、surface 仍有效、尺寸/DPI 与目标一致 → 复用；
 * 任一条件不满足就重建，并在原因里写清楚（参数漂移要带旧/新值）。
 */
class VdReusePolicyTest {

    private val target = VdSnapshot(displayId = -1, width = 1926, height = 1054, dpi = 234, alive = true)

    @Test
    fun `no existing vd always creates`() {
        val d = VdReusePolicy.decide(current = null, target = target, surfaceValid = true)
        assertFalse(d.reuse)
        assertTrue(d.reason.contains("还没有"))
    }

    @Test
    fun `released display is never reused even if the object reference is still there`() {
        // 现场陷阱：VirtualDisplay.release() 后字段仍非 null，但 display 已销毁。
        val dead = VdSnapshot(displayId = 2, width = 1926, height = 1054, dpi = 234, alive = false)
        val d = VdReusePolicy.decide(current = dead, target = target, surfaceValid = true)
        assertFalse("不能复用已销毁的 VD", d.reuse)
        assertTrue(d.reason.contains("已销毁"))
    }

    @Test
    fun `invalid surface forces a recreate`() {
        val alive = VdSnapshot(displayId = 2, width = 1926, height = 1054, dpi = 234, alive = true)
        val d = VdReusePolicy.decide(current = alive, target = target, surfaceValid = false)
        assertFalse(d.reuse)
        assertTrue(d.reason.contains("surface"))
    }

    @Test
    fun `identical size and dpi reuses without touching tasks`() {
        val alive = VdSnapshot(displayId = 2, width = 1926, height = 1054, dpi = 234, alive = true)
        val d = VdReusePolicy.decide(current = alive, target = target, surfaceValid = true)
        assertTrue(d.reuse)
        assertTrue(d.reason.contains("display=2"))
    }

    @Test
    fun `parameter drift recreates and explains both sides`() {
        // 现场 id=2(1926x1054@234) → id=3(2560x1402@302)：档位从 0 切到 1 后必须重建。
        val old = VdSnapshot(displayId = 2, width = 1926, height = 1054, dpi = 234, alive = true)
        val d = VdReusePolicy.decide(
            current = old,
            target = VdSnapshot(displayId = -1, width = 2560, height = 1402, dpi = 302, alive = true),
            surfaceValid = true
        )
        assertFalse(d.reuse)
        assertEquals(
            "参数漂移：旧 1926x1054@234dpi → 新 2560x1402@302dpi",
            d.reason
        )
    }

    @Test
    fun `dpi-only drift still forces a recreate`() {
        val old = VdSnapshot(displayId = 4, width = 1926, height = 1054, dpi = 234, alive = true)
        val d = VdReusePolicy.decide(
            current = old,
            target = VdSnapshot(displayId = -1, width = 1926, height = 1054, dpi = 302, alive = true),
            surfaceValid = true
        )
        assertFalse(d.reuse)
        assertTrue(d.reason.contains("@234dpi") && d.reason.contains("@302dpi"))
    }
}
