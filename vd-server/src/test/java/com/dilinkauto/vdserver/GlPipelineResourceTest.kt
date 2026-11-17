package com.dilinkauto.vdserver

import android.opengl.EGL14
import android.opengl.EGLDisplay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GlPipelineResourceTest {

    @Test
    fun cleanupReleasesProgramTextureAndTerminatesDisplay() {
        val pipeline = GlPipeline(
            displayWidth = 1920,
            displayHeight = 1080,
            encodeWidth = 1920,
            encodeHeight = 1080,
            fps = 30,
            frameIntervalNanos = 33_333_333L,
            bitrate = 8_000_000
        )

        val dummyDisplay = EGLDisplay::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        pipeline.setTestResources(program = 101, textureId = 202, display = dummyDisplay)

        val deletedProgram = AtomicInteger(0)
        val deletedTexture = AtomicInteger(0)
        val terminatedDisplay = AtomicBoolean(false)

        pipeline.cleanup(
            deleteProgram = { deletedProgram.set(it) },
            deleteTextures = { deletedTexture.set(it) },
            terminateDisplay = {
                if (it === dummyDisplay) terminatedDisplay.set(true)
            }
        )

        assertEquals("必须释放着色器程序", 101, deletedProgram.get())
        assertEquals("必须释放外部纹理", 202, deletedTexture.get())
        assertEquals("必须显式调用 eglTerminate 释放 EGLDisplay", true, terminatedDisplay.get())

        // 验证清理幂等性：二次 cleanup 不重复调用
        deletedProgram.set(0)
        deletedTexture.set(0)
        terminatedDisplay.set(false)

        pipeline.cleanup(
            deleteProgram = { deletedProgram.set(it) },
            deleteTextures = { deletedTexture.set(it) },
            terminateDisplay = { terminatedDisplay.set(true) }
        )

        assertEquals(0, deletedProgram.get())
        assertEquals(0, deletedTexture.get())
        assertEquals(false, terminatedDisplay.get())
    }
}
