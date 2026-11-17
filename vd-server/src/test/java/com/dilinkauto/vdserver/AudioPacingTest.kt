package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacingTest {

    private val second = 1_000_000_000L

    @Test
    fun baselineEstablishedOnFirstSilenceQuery() {
        val pacing = AudioPacing(frameDurationNanos = second)
        assertEquals(0, pacing.silenceFramesDue(0L))
        assertEquals(0, pacing.silenceFramesDue(second - 1))
        assertEquals(1, pacing.silenceFramesDue(second))
    }

    @Test
    fun realDataResetsDueTime() {
        val pacing = AudioPacing(frameDurationNanos = second)
        pacing.onRealData(10L * second)
        // 10s 后才开始，10s 和 10.5s 都不应触发补帧
        assertEquals(0, pacing.silenceFramesDue(10L * second))
        assertEquals(0, pacing.silenceFramesDue(10L * second + 500_000_000L))
        // 刚好到了 11s 触发 1 帧
        assertEquals(1, pacing.silenceFramesDue(11L * second))
    }

    @Test
    fun catchUpCappedAtMaxFrames() {
        val maxCatchUp = 3
        val pacing = AudioPacing(frameDurationNanos = second, maxCatchUpFrames = maxCatchUp)
        pacing.onRealData(0L)
        // 停流很久（如 10s 后）
        val due = pacing.silenceFramesDue(10L * second)
        assertEquals(maxCatchUp, due)
    }

    @Test
    fun invalidPacingArguments() {
        fun expectFailure(block: () -> Unit) {
            var failed = false
            try { block() } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
        }
        expectFailure { AudioPacing(frameDurationNanos = 0) }
        expectFailure { AudioPacing(frameDurationNanos = -100) }
        expectFailure { AudioPacing(frameDurationNanos = second, maxCatchUpFrames = 0) }
        expectFailure { AudioPacing(frameDurationNanos = second, maxCatchUpFrames = -1) }
    }
}
