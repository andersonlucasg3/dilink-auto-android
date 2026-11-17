package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

class GlPipelineTimestampTest {

    @Test
    fun testComputeFramePtsWithValidSurfaceTextureTimestamp() {
        val stTimestamp = 123_456_789_000L
        val nowNanos = 200_000_000_000L
        val pts = GlPipeline.computeFramePtsNanos(hasNewFrame = true, surfaceTextureTimestamp = stTimestamp, nowNanos = nowNanos)
        assertEquals(stTimestamp, pts)
    }

    @Test
    fun testComputeFramePtsFallbackWhenNoNewFrameOrZeroTimestamp() {
        val nowNanos = 200_000_000_000L
        val pts1 = GlPipeline.computeFramePtsNanos(hasNewFrame = false, surfaceTextureTimestamp = 123_456_789_000L, nowNanos = nowNanos)
        assertEquals(nowNanos, pts1)

        val pts2 = GlPipeline.computeFramePtsNanos(hasNewFrame = true, surfaceTextureTimestamp = 0L, nowNanos = nowNanos)
        assertEquals(nowNanos, pts2)
    }
}
