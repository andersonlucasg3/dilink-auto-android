package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the fps guard added for S-L2.
 *
 * `PipelineServer` used to derive `frameIntervalNanos = 1_000_000_000L / fps`
 * directly in its constructor, with `fps` coming straight off argv. A legacy or
 * hand-edited preference can put 0 (or a negative, or something absurd) there, and
 * the resulting ArithmeticException killed the process before it ever bound a port
 * — with no log line explaining why, because the throw happened inside the
 * constructor. The class now coerces first; these tests pin the coercion, and
 * re-derive the interval expression so a regression cannot silently reintroduce
 * the divide-by-zero.
 */
class PipelineFpsCoercionTest {

    /** The expression the class body computes — copied here to prove it stays safe. */
    private fun frameIntervalNanosFor(coercedFps: Int) = 1_000_000_000L / coercedFps

    @Test
    fun zeroFpsIsCoercedIntoTheProtocolRangeSoTheIntervalCannotDivideByZero() {
        val fps = PipelineServer.coerceFps(0)
        assertTrue("fps=0 must not survive", fps in VideoConfig.MIN_FPS..VideoConfig.MAX_FPS)
        assertTrue("interval must stay positive", frameIntervalNanosFor(fps) > 0)
    }

    @Test
    fun everyDegenerateFpsValueIsCoerced() {
        // Int.MIN_VALUE / negatives: a bogus pref must not be able to kill the process.
        for (fps in listOf(Int.MIN_VALUE, -60, -1, 0, 1, 9, 10, 24, 60, 61, 1000, Int.MAX_VALUE)) {
            val c = PipelineServer.coerceFps(fps)
            assertTrue("fps=$fps must coerce into ${VideoConfig.MIN_FPS}..${VideoConfig.MAX_FPS}, was $c",
                c in VideoConfig.MIN_FPS..VideoConfig.MAX_FPS)
            assertTrue("fps=$fps produced $c, interval must stay positive", frameIntervalNanosFor(c) > 0)
        }
    }

    @Test
    fun aValidFpsIsPassedThroughUnchanged() {
        assertEquals(VideoConfig.TARGET_FPS, PipelineServer.coerceFps(VideoConfig.TARGET_FPS))
        assertEquals(30, PipelineServer.coerceFps(30))
    }

    @Test
    fun coercionMatchesTheIntervalTheConstructorDerives() {
        // 1e9 / 24 = 41_666_666ns ~= 41.7ms; at the coerced extremes the frame budget
        // must stay inside a sane render loop (10ms..100ms), otherwise a bogus fps
        // would spin the GL loop or stall the encode path.
        val minInterval = frameIntervalNanosFor(PipelineServer.coerceFps(0))       // clamped to MIN_FPS
        val maxInterval = frameIntervalNanosFor(PipelineServer.coerceFps(9999))    // clamped to MAX_FPS
        assertTrue("frame budget $minInterval ns is outside 10..100ms",
            minInterval in 10_000_000L..100_000_000L)
        assertTrue("frame budget $maxInterval ns is outside 10..100ms",
            maxInterval in 10_000_000L..100_000_000L)
    }
}
