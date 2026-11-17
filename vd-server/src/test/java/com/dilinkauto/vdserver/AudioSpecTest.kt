package com.dilinkauto.vdserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpecTest {

    @Test
    fun defaultSpecParameters() {
        val spec = AudioSpec.DEFAULT
        assertEquals(48_000, spec.sampleRate)
        assertEquals(2, spec.channelCount)
        assertEquals(128_000, spec.bitrate)
        assertEquals(1024, spec.samplesPerFrame)
        assertEquals(4096, spec.frameBytes)
        assertEquals(21_333_333L, spec.frameDurationNanos)
        assertEquals(46.875, spec.framesPerSecond, 1e-9)
    }

    @Test
    fun customSpecCalculations() {
        val spec = AudioSpec(sampleRate = 44_100, channelCount = 1, bitrate = 64_000, samplesPerFrame = 512)
        assertEquals(1024, spec.frameBytes) // 512 * 1 * 2
        assertEquals(11_609_977L, spec.frameDurationNanos) // 512 * 1e9 / 44100
        assertEquals(44100.0 / 512.0, spec.framesPerSecond, 1e-9)
    }

    @Test
    fun parameterValidation() {
        fun expectFailure(block: () -> Unit) {
            var failed = false
            try { block() } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
        }

        expectFailure { AudioSpec(sampleRate = 0) }
        expectFailure { AudioSpec(sampleRate = -1) }
        expectFailure { AudioSpec(channelCount = 0) }
        expectFailure { AudioSpec(channelCount = 3) }
        expectFailure { AudioSpec(bitrate = 0) }
        expectFailure { AudioSpec(samplesPerFrame = 0) }
    }

    @Test
    fun ptsUsCalculationAvoidsOverflow() {
        val spec = AudioSpec.DEFAULT
        // 验证 1 小时后的帧号计算，不产生整型溢出
        val hourFrameIndex = (3600.0 * 46.875).toLong()
        val ptsUs = ptsUsForFrame(hourFrameIndex, spec)
        val expectedUs = hourFrameIndex * 1024L * 1_000_000L / 48_000L
        assertEquals(expectedUs, ptsUs)
        assertTrue(ptsUs > 0)
    }

    @Test
    fun silenceFrameMatchesExpectedSizeAndZeros() {
        val spec = AudioSpec.DEFAULT
        val silence = silenceFrame(spec)
        assertEquals(4096, silence.size)
        assertArrayEquals(ByteArray(4096), silence)
    }
}
