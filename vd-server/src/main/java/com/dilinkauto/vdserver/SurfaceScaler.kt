package com.dilinkauto.vdserver

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * GPU-accelerated surface scaler.
 * Renders a SurfaceTexture (VD content at full resolution) onto an output Surface
 * (encoder input at car viewport resolution) using OpenGL ES.
 *
 * All EGL/GL operations happen on the scaler thread (EGL contexts are thread-local).
 * Call start() to launch the thread, then getInputSurface() (blocks until ready).
 */
class SurfaceScaler(
    private val encoderSurface: Surface,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val frameIntervalMs: Long
) {
    @Volatile private var _inputSurface: Surface? = null
    @Volatile private var running = true

    private val readyLatch = CountDownLatch(1)
    private var thread: Thread? = null
    private var callbackThread: android.os.HandlerThread? = null

    /** Start the scaler thread. Call getInputSurface() after this. */
    fun start() {
        thread = Thread(::run, "SurfaceScaler").apply {
            isDaemon = true
            start()
        }
    }

    /** The Surface that the VirtualDisplay should render into. Blocks until ready. */
    fun getInputSurface(): Surface {
        try { readyLatch.await() } catch (_: InterruptedException) {}
        return _inputSurface!!
    }

    private fun run() {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // GL program
        val program = createProgram()
        GLES20.glUseProgram(program)

        // External texture for SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // SurfaceTexture — the VD renders here
        val surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(inputWidth, inputHeight)
        _inputSurface = Surface(surfaceTexture)

        // Signal that the input surface is ready
        readyLatch.countDown()

        // Frame sync
        val frameLock = Any()
        val frameAvailable = booleanArrayOf(false)
        val ht = android.os.HandlerThread("ScalerCB").apply { start() }
        callbackThread = ht
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable[0] = true
                (frameLock as java.lang.Object).notifyAll()
            }
        }, android.os.Handler(ht.looper))

        // Fullscreen quad — Y texcoords flipped (SurfaceTexture is top-down, GL is bottom-up)
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        val vertexBuf: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuf.put(vertices).position(0)

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glViewport(0, 0, outputWidth, outputHeight)

        // Render loop — draws at least once per frameIntervalMs even on static content
        var swapCount = 0L
        var newFrameCount = 0L
        var idleSwapCount = 0L
        println("[SurfaceScaler] Render loop starting, frameIntervalMs=$frameIntervalMs")

        while (running) {
            val hasNewFrame: Boolean
            synchronized(frameLock) {
                if (!frameAvailable[0] && running) {
                    try { (frameLock as java.lang.Object).wait(frameIntervalMs) } catch (_: InterruptedException) {}
                }
                hasNewFrame = frameAvailable[0]
                frameAvailable[0] = false
            }
            if (!running) break

            if (hasNewFrame) {
                surfaceTexture.updateTexImage()
                newFrameCount++
            } else {
                idleSwapCount++
            }
            swapCount++
            if (swapCount <= 3 || swapCount % 30 == 0L) {
                println("[SurfaceScaler] swap #$swapCount newFrames=$newFrameCount idleSwaps=$idleSwapCount hasNew=$hasNewFrame")
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

            vertexBuf.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
            GLES20.glEnableVertexAttribArray(aPosition)

            vertexBuf.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
            GLES20.glEnableVertexAttribArray(aTexCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        // Cleanup
        _inputSurface?.release()
        surfaceTexture.release()
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            try { it.join(2000) } catch (_: InterruptedException) {}
        }
        callbackThread?.let {
            it.quitSafely()
            callbackThread = null
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aPosition;
              vTexCoord = aTexCoord;
            }"""

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }"""

        private fun createProgram(): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            return GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glLinkProgram(prog)
            }
        }

        private fun loadShader(type: Int, source: String): Int =
            GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
            }
    }
}
