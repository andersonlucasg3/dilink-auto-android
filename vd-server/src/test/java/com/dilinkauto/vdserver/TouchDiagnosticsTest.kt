package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TouchDiagnostics` —— issue #64 的触控链路统计与节流。
 *
 * 钉住三件事：
 *  - DOWN/UP 逐条落日志并计数；
 *  - 多指批次「前 10 条 + 每 50 条」的节流，避免 MOVE 风暴刷爆 vd-server.log；
 *  - `[TouchStats]` 每 10s 才输出一次，首次调用只建立时间基线。
 */
class TouchDiagnosticsTest {

    private fun rig(intervalMs: Long = 10_000L): Pair<TouchDiagnostics, MutableList<String>> {
        val lines = mutableListOf<String>()
        return TouchDiagnostics({ lines += it }, intervalMs) to lines
    }

    @Test
    fun `down and up are logged with mapped coordinates`() {
        val (diag, lines) = rig()

        diag.noteDown(1_000L, ptr = 0, x = 704, y = 198, displayId = 7)
        diag.noteUp(1_000L, ptr = 0, x = 704, y = 198, displayId = 7)

        assertTrue(lines.any { it == "[Touch] DOWN ptr=0 (704,198) display=7" })
        assertTrue(lines.any { it == "[Touch] UP ptr=0 (704,198) display=7" })
        assertEquals(1L, diag.downs)
        assertEquals(1L, diag.ups)
    }

    @Test
    fun `multi pointer batches are throttled after the first ten`() {
        val (diag, lines) = rig()
        repeat(12) { diag.noteMoveBatch(1_000L, listOf(Triple(0, 1, 1), Triple(1, 2, 2))) }

        assertEquals("前 10 条多指批次必须全记", 10, lines.count { "MOVE n=2" in it })
        assertEquals(12L, diag.multiBatches)
        assertEquals(12L, diag.moveBatches)
        assertEquals(24L, diag.movePointers)
    }

    @Test
    fun `stats are emitted on the interval only`() {
        val (diag, lines) = rig(intervalMs = 10_000L)

        diag.noteDown(1_000L, ptr = 0, x = 10, y = 20, displayId = 7) // 建基线，不出统计
        assertTrue("首次调用不应立刻输出统计", lines.none { "[TouchStats]" in it })

        diag.noteMoveBatch(10_999L, listOf(Triple(0, 10, 20)))
        assertTrue("未到 10s 不应输出", lines.none { "[TouchStats]" in it })

        diag.noteMoveBatch(11_000L, listOf(Triple(0, 10, 20)))
        val stats = lines.single { "[TouchStats]" in it }
        assertTrue(stats.contains("recv=0"))
        assertTrue(stats.contains("down=1"))
        assertTrue(stats.contains("moveBatches=2"))
    }

    @Test
    fun `out of bounds is recorded and logged`() {
        val (diag, lines) = rig()

        diag.noteOutOfBounds(1_000L, ptr = 0, x = 1408, y = 792, width = 1408, height = 792)

        assertEquals(1L, diag.outOfBounds)
        assertTrue(lines.any { "OUT_OF_BOUNDS ptr=0 (1408,792) display=1408x792" in it })
    }

    @Test
    fun `summary carries every counter`() {
        val (diag, _) = rig()
        diag.noteFrame()
        diag.noteDown(1_000L, 0, 1, 2, 7)
        diag.noteUp(1_000L, 0, 1, 2, 7)
        diag.noteMoveBatch(1_000L, listOf(Triple(0, 1, 2), Triple(1, 3, 4)))
        diag.noteSynthDown(1_000L, 1, 3, 4)
        diag.noteOutOfBounds(1_000L, 0, 9, 9, 8, 8)
        diag.noteInjectFail()
        diag.noteRejectedId()

        val s = diag.summary()
        assertTrue(s.contains("recv=1"))
        assertTrue(s.contains("down=1"))
        assertTrue(s.contains("up=1"))
        assertTrue(s.contains("moveBatches=1"))
        assertTrue(s.contains("movePts=2"))
        assertTrue(s.contains("multi=1"))
        assertTrue(s.contains("synth=1"))
        assertTrue(s.contains("oob=1"))
        assertTrue(s.contains("fails=1"))
        assertTrue(s.contains("rejected=1"))
    }
}