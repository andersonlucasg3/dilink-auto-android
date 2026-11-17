package com.dilinkauto.server.decoder

import com.dilinkauto.protocol.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the S-L2 fix: a persisted startup-FPS preference of `0` (legacy or
 * hand-edited) used to reach `frameQueue.poll(1000L / fps, ...)` inside the
 * feed thread and raise `ArithmeticException`, killing decode with no
 * recovery path.
 *
 * [VideoDecoder.coerceFps] is the single clamp both the poll timeout and
 * `MediaFormat.KEY_OPERATING_RATE` consume, so testing the pure function
 * covers the whole division-by-zero surface. The rest of the decoder
 * (MediaCodec create/configure, the feed thread, `stop()`'s join) requires a
 * device and is covered by the manual BYD-session notes in the source.
 */
class VideoDecoderFpsTest {

    @Test
    fun fpsOfZeroIsCoercedToMinimum() {
        assertEquals(VideoConfig.MIN_FPS, VideoDecoder.coerceFps(0))
    }

    @Test
    fun negativeFpsIsCoercedToMinimum() {
        assertEquals(VideoConfig.MIN_FPS, VideoDecoder.coerceFps(-24))
        assertEquals(VideoConfig.MIN_FPS, VideoDecoder.coerceFps(Int.MIN_VALUE))
    }

    @Test
    fun absurdFpsIsCoercedToMaximum() {
        assertEquals(VideoConfig.MAX_FPS, VideoDecoder.coerceFps(1_000))
        assertEquals(VideoConfig.MAX_FPS, VideoDecoder.coerceFps(Int.MAX_VALUE))
    }

    @Test
    fun inRangeFpsIsUntouched() {
        assertEquals(VideoConfig.TARGET_FPS, VideoDecoder.coerceFps(VideoConfig.TARGET_FPS))
        assertEquals(VideoConfig.MIN_FPS, VideoDecoder.coerceFps(VideoConfig.MIN_FPS))
        assertEquals(VideoConfig.MAX_FPS, VideoDecoder.coerceFps(VideoConfig.MAX_FPS))
    }

    @Test
    fun coercedFpsIsAlwaysSafeAsDivisor() {
        // 1000L / fps must never divide by zero or produce a 0ms timeout.
        for (fps in intArrayOf(0, -1, 1, 24, 60, 100, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val safe = VideoDecoder.coerceFps(fps)
            assert(safe >= 1) { "coerced fps $safe must be >= 1" }
            val pollMs = 1000L / safe
            assert(pollMs >= 1) { "poll timeout $pollMs must be >= 1ms" }
        }
    }
}
