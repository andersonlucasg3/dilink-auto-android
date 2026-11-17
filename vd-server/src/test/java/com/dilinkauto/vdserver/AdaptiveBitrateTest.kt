package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the adaptive-bitrate policy that [GlPipeline] used to hold inline.
 *
 * The two reactions are asymmetric on purpose — recovery waits for a sustained
 * clean interval while back-pressure acts on a single slow write — so these
 * tests pin the asymmetry rather than just the arithmetic.
 */
class AdaptiveBitrateTest {

    private fun slowWrite() = 20L * 1_000_000L   // 20ms > 15ms threshold
    private fun fastWrite() = 5L * 1_000_000L    // 5ms

    @Test
    fun startsAtTheConfiguredCeiling() {
        val ab = AdaptiveBitrate(8_000_000)
        assertEquals(8_000_000, ab.currentBitrate)
        assertFalse(ab.needsSyncFrame)
    }

    @Test
    fun oneSlowWriteDropsTo75PercentAndAsksForASyncFrame() {
        val ab = AdaptiveBitrate(8_000_000)
        assertTrue(ab.onFrameWritten(slowWrite()))
        assertEquals(6_000_000, ab.currentBitrate)
        assertTrue("drop must request a sync frame", ab.needsSyncFrame)
    }

    @Test
    fun syncFrameRequestIsOneShotAndClearedOnApply() {
        val ab = AdaptiveBitrate(8_000_000)
        ab.onFrameWritten(slowWrite())
        assertTrue(ab.needsSyncFrame)
        ab.onBitrateApplied()
        assertFalse(ab.needsSyncFrame)
    }

    @Test
    fun neverDropsBelowTheFloor() {
        val ab = AdaptiveBitrate(2_000_000)
        repeat(20) { ab.onFrameWritten(slowWrite()) }
        assertEquals(AdaptiveBitrate.MIN_BITRATE, ab.currentBitrate)
    }

    @Test
    fun slowWriteAtTheFloorIsNotAnErrorAndReportsNoChange() {
        val ab = AdaptiveBitrate(AdaptiveBitrate.MIN_BITRATE)
        // 75% of the floor is below the floor, so the clamp makes it a no-op.
        assertFalse(ab.onFrameWritten(slowWrite()))
        assertEquals(AdaptiveBitrate.MIN_BITRATE, ab.currentBitrate)
    }

    @Test
    fun fastWritesNeverDropTheRate() {
        val ab = AdaptiveBitrate(8_000_000)
        repeat(50) { assertFalse(ab.onFrameWritten(fastWrite())) }
        assertEquals(8_000_000, ab.currentBitrate)
    }

    @Test
    fun recoveryRequiresTheFullCleanInterval() {
        val ab = AdaptiveBitrate(8_000_000)
        ab.onFrameWritten(slowWrite())          // drop to 6 Mbps
        assertEquals(6_000_000, ab.currentBitrate)

        // One fast write arms the clean timer but must not recover yet.
        assertFalse(ab.onFrameWritten(fastWrite()))
        assertEquals(6_000_000, ab.currentBitrate)
    }

    @Test
    fun aSlowWriteResetsRecoveryProgress() {
        val ab = AdaptiveBitrate(8_000_000)
        ab.onFrameWritten(fastWrite())          // arm clean timer
        ab.onFrameWritten(slowWrite())          // drop; resets the timer
        assertEquals(6_000_000, ab.currentBitrate)
        // Recovery cannot start from a reset timer.
        assertFalse(ab.onFrameWritten(fastWrite()))
        assertEquals(6_000_000, ab.currentBitrate)
    }

    @Test
    fun recoveryClimbsOneStepPerIntervalAndStopsAtTheCeiling() {
        val ab = AdaptiveBitrate(
            ceiling = 8_000_000,
            slowWriteMillis = 15L,
            cleanIntervalMillis = 0L   // every fast frame is a recovery opportunity
        )
        // Must drop first — recovery only ever climbs back toward the ceiling.
        ab.onFrameWritten(slowWrite())
        assertEquals(6_000_000, ab.currentBitrate)

        var sawChange = false
        repeat(50) {
            if (ab.onFrameWritten(fastWrite())) sawChange = true
            assertTrue("must never exceed the ceiling", ab.currentBitrate <= 8_000_000)
        }
        assertTrue("rate must climb back at least one step", sawChange)
        assertEquals("must converge back to the ceiling", 8_000_000, ab.currentBitrate)
    }

    @Test
    fun aCeilingBelowTheFloorIsRaisedToTheFloor() {
        val ab = AdaptiveBitrate(100_000)
        assertEquals(AdaptiveBitrate.MIN_BITRATE, ab.currentBitrate)
    }

    @Test
    fun recoveryDoesNotSpikeOnConsecutiveFramesAfterIntervalElapsed() {
        val ab = AdaptiveBitrate(
            ceiling = 8_000_000,
            slowWriteMillis = 15L,
            cleanIntervalMillis = 30L
        )
        // 先触发一次降级：8M -> 6M
        ab.onFrameWritten(slowWrite())
        assertEquals(6_000_000, ab.currentBitrate)

        // 第一帧快速写入，激活清洁计时器
        ab.onFrameWritten(fastWrite())
        Thread.sleep(40)

        // 达到 30ms 清洁间隔后第一帧应爬升一个步长 (500k)
        assertTrue("达到清洁间隔后第一帧应爬升", ab.onFrameWritten(fastWrite()))
        assertEquals(6_500_000, ab.currentBitrate)

        // 紧接着的一帧（未满下一个清洁间隔）绝对不得连续爬升
        assertFalse("爬升后下一帧未满 cleanIntervalMillis 不得连续爬升", ab.onFrameWritten(fastWrite()))
        assertEquals(6_500_000, ab.currentBitrate)
    }
}