package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerTimingTest {

    @Test
    fun testNormalProgressionAdvancesByFrameNanos() {
        val frameNanos = 21_333_333L // ~21.3ms for 48kHz
        val currentTick = 1_000_000_000L
        val now = 1_010_000_000L // within frame window (10ms after)

        val next = AudioPlayer.calculateNextTick(currentTick, now, frameNanos)
        assertEquals(currentTick + frameNanos, next)
    }

    @Test
    fun testUnderrunRecoveryResetsBaselineToNow() {
        val frameNanos = 21_333_333L
        val currentTick = 1_000_000_000L
        // 500ms lag due to GC or system stutter
        val now = 1_500_000_000L

        val next = AudioPlayer.calculateNextTick(currentTick, now, frameNanos)
        // Should align to now + frameNanos rather than currentTick + frameNanos
        assertEquals(now + frameNanos, next)
    }
}
