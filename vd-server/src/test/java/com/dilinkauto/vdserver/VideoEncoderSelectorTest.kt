package com.dilinkauto.vdserver

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoderSelectorTest {

    private fun createDummyCodec(): MediaCodec =
        MediaCodec::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun createDummySurface(): Surface =
        Surface::class.java.getDeclaredConstructor(android.graphics.SurfaceTexture::class.java)
            .apply { isAccessible = true }.newInstance(null as android.graphics.SurfaceTexture?)

    @Test
    fun probeCodecReleasesSurfaceAndCodecWhenStartFails() {
        val format = MediaFormat()
        val dummyCodec = createDummyCodec()
        val dummySurface = createDummySurface()

        val configureCalled = AtomicBoolean(false)
        val surfaceReleased = AtomicBoolean(false)
        val codecReleased = AtomicBoolean(false)

        try {
            VideoEncoderSelector.probeCodec(
                codec = dummyCodec,
                format = format,
                configure = { _, _ -> configureCalled.set(true) },
                createInputSurface = { dummySurface },
                start = { throw IllegalStateException("codec.start failed") },
                releaseCodec = { codecReleased.set(true) },
                releaseSurface = {
                    if (it === dummySurface) surfaceReleased.set(true)
                }
            )
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("codec.start failed", e.message)
        }

        assertTrue("configure 应被调用", configureCalled.get())
        assertTrue("start 失败时必须显式 release 输入 Surface 防止 BufferQueue 泄漏", surfaceReleased.get())
        assertTrue("start 失败时必须 release codec", codecReleased.get())
    }

    @Test
    fun probeCodecDoesNotReleaseSurfaceIfCreateInputSurfaceFails() {
        val format = MediaFormat()
        val dummyCodec = createDummyCodec()

        val surfaceReleased = AtomicBoolean(false)
        val codecReleased = AtomicBoolean(false)

        try {
            VideoEncoderSelector.probeCodec(
                codec = dummyCodec,
                format = format,
                configure = { _, _ -> },
                createInputSurface = { throw RuntimeException("createInputSurface failed") },
                start = {},
                releaseCodec = { codecReleased.set(true) },
                releaseSurface = { surfaceReleased.set(true) }
            )
            fail("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("createInputSurface failed", e.message)
        }

        assertFalse("surface 创建失败时不应调用 releaseSurface", surfaceReleased.get())
        assertTrue("创建 surface 失败时 codec 仍必须被 release", codecReleased.get())
    }

    @Test
    fun probeCodecReturnsSurfaceOnSuccessWithoutReleasing() {
        val format = MediaFormat()
        val dummyCodec = createDummyCodec()
        val dummySurface = createDummySurface()

        val surfaceReleased = AtomicBoolean(false)
        val codecReleased = AtomicBoolean(false)

        val surface = VideoEncoderSelector.probeCodec(
            codec = dummyCodec,
            format = format,
            configure = { _, _ -> },
            createInputSurface = { dummySurface },
            start = {},
            releaseCodec = { codecReleased.set(true) },
            releaseSurface = { surfaceReleased.set(true) }
        )

        assertTrue(surface === dummySurface)
        assertFalse(surfaceReleased.get())
        assertFalse(codecReleased.get())
    }
}
