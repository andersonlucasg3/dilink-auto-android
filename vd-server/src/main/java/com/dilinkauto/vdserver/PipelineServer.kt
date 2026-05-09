package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.*
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import com.dilinkauto.protocol.*
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport

/**
 * Single-threaded video pipeline server running as app_process (shell UID 2000).
 *
 * Architecture: one pipeline thread does clock → grab VD frame → GL render →
 * encoder drain → TCP write. No queues between stages — flow control is natural.
 *
 * Usage: CLASSPATH=<jar> app_process / com.dilinkauto.vdserver.PipelineServer W H DPI PHONE_HOST EW EH FPS
 */
class PipelineServer(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val dpi: Int,
    private val phoneHost: String,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val fps: Int
) {
    private val frameIntervalNanos = 1_000_000_000L / fps

    @Volatile private var running = true
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    // Input injection (ported from VirtualDisplayServer)
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private var touchDownTime = 0L

    // Shell
    private var persistentShell: Process? = null
    private var shellInput: OutputStream? = null

    // Display power
    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null
    private var lastPowerOffTime = 0L

    // EGL/GL resources (created in runPipeline, used only on pipeline thread)
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var stTexture: android.graphics.SurfaceTexture? = null
    private var stTexId = 0
    private var glProgram = 0
    private var glPosLoc = 0
    private var glTexLoc = 0
    private var quadBuf: FloatBuffer? = null
    private var vdInputSurface: Surface? = null
    private val readyLatch = CountDownLatch(1)

    // ── Companion ──

    companion object {
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11
        private const val MSG_FOCUSED_APP: Byte = 0x12
        private const val MSG_SHORTCUTS_RESULT: Byte = 0x13
        private const val CMD_STOP = 0xFF
        private const val CMD_QUERY_SHORTCUTS: Byte = 0x25

        private const val BITRATE = 8_000_000
        private const val I_FRAME_INTERVAL = 1
        private const val MAX_POINTERS = 10

        private const val VIDEO_PORT = 9638
        private const val INPUT_PORT = 9639
        private const val LIFECYCLE_PORT = 19647

        private var displayControlClass: Class<*>? = null
        private var displayControlLoaded = false

        @JvmStatic fun main(args: Array<String>) {
            val w = args.getOrNull(0)?.toInt() ?: 1408
            val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120
            val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w
            val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: 30
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f")
            PipelineServer(w, h, d, ph, ew, eh, f).run()
        }

        private fun log(msg: String) { System.out.println("[Pipeline] $msg"); System.out.flush() }
        private fun err(msg: String) { System.err.println("[Pipeline] $msg"); System.err.flush() }
    }

    // ── Entry point ──

    fun run() {
        initInputManager()
        initPersistentShell()
        try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
        if (!createVirtualDisplay()) { err("Fatal: failed to create VD"); return }
        val conns = bindAndAccept() ?: return
        startLifecycleReader(conns.phoneChannel)
        val pipelineThread = Thread({ runPipeline(conns.carVideo) }, "Pipeline").apply { start() }
        startTouchReader(conns.carInput)
        try { pipelineThread.join() } catch (_: InterruptedException) {}
        cleanup()
    }

    // ── Phase 1: Setup ──

    private fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as android.os.IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            injectInputEventMethod = inputManager!!.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            try { setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType) }
            catch (_: Exception) {}
            log("InputManager injection ready")
        } catch (e: Exception) { err("InputManager init failed: ${e.message}") }
    }

    private fun initPersistentShell() {
        try {
            persistentShell = Runtime.getRuntime().exec(arrayOf("sh"))
            shellInput = persistentShell!!.outputStream
        } catch (e: Exception) { err("Persistent shell failed: ${e.message}") }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_LATENCY, 0)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        format.setLong("repeat-previous-frame-after", 500_000L)
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${BITRATE / 1_000_000}Mbps Main 30fps")
    }

    private fun createVirtualDisplay(): Boolean {
        val enc = encoder ?: return false
        val encSurf = enc.createInputSurface()
        enc.start()

        // Init EGL
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2); EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttribs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val nc = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, nc, 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encSurf, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // GL program
        glProgram = createProgram()
        GLES20.glUseProgram(glProgram)
        glPosLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        glTexLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        GLES20.glViewport(0, 0, encodeWidth, encodeHeight)

        // SurfaceTexture for VD input
        val texIds = IntArray(1); GLES20.glGenTextures(1, texIds, 0); stTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        stTexture = android.graphics.SurfaceTexture(stTexId)
        stTexture!!.setDefaultBufferSize(displayWidth, displayHeight)
        vdInputSurface = Surface(stTexture)

        // Fullscreen quad
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadBuf!!.put(quad).position(0)

        readyLatch.countDown()
        log("EGL/GL ready, VD input surface created")

        // Create VirtualDisplay
        val vdSurf = vdInputSurface!!
        try {
            log("Trying DisplayManagerGlobal approach...")
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmgClass.getDeclaredMethod("getInstance")
            val dmg = getInstance.invoke(null)
            log("Got DisplayManagerGlobal instance")
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = builderClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance("DiLinkAutoVD", displayWidth, displayHeight, dpi)
            builderClass.getDeclaredMethod("setSurface", Surface::class.java).invoke(builder, vdSurf)
            builderClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, 0x6c49)
            val config = builderClass.getDeclaredMethod("build").invoke(builder)
            log("VirtualDisplayConfig: displayIdToMirror=0")
            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            try {
                virtualDisplay = dmgClass.getDeclaredMethod("createVirtualDisplay", configClass, cbClass, android.os.Handler::class.java, String::class.java).invoke(dmg, config, null, null, "com.android.shell") as VirtualDisplay?
            } catch (e: NoSuchMethodException) {
                virtualDisplay = dmgClass.getDeclaredMethod("createVirtualDisplay", configClass, cbClass, String::class.java).invoke(dmg, config, null, "com.android.shell") as VirtualDisplay?
            }
            if (virtualDisplay != null) {
                val idField = virtualDisplay!!.javaClass.getDeclaredField("mDisplayId"); idField.isAccessible = true
                displayId = idField.getInt(virtualDisplay)
                log("VirtualDisplay created via DisplayManagerGlobal: displayId=$displayId")
            }
        } catch (e: Exception) {
            err("DisplayManagerGlobal failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        if (virtualDisplay == null) {
            try {
                log("Trying DisplayManager + mDisplayIdToMirror bypass...")
                val dmClass = Class.forName("android.hardware.display.DisplayManager")
                val ctx = FakeContext.get()
                val dm = dmClass.getConstructor(android.content.Context::class.java).newInstance(ctx)
                try {
                    val mirrorField = dmClass.getDeclaredField("mDisplayIdToMirror"); mirrorField.isAccessible = true
                    mirrorField.setInt(dm, 0); log("Set mDisplayIdToMirror=0")
                } catch (e2: Exception) { err("mDisplayIdToMirror reflection failed: ${e2.message}") }
                val flags = 0x6c49
                log("Creating VD with flags=0x${flags.toString(16)}")
                virtualDisplay = dmClass.getDeclaredMethod("createVirtualDisplay", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java, Int::class.javaPrimitiveType).invoke(dm, "DiLinkAutoVD", displayWidth, displayHeight, dpi, vdSurf, flags) as VirtualDisplay?
                val idField = virtualDisplay!!.javaClass.getDeclaredField("mDisplayId"); idField.isAccessible = true
                displayId = idField.getInt(virtualDisplay)
                log("VirtualDisplay created via DisplayManager: displayId=$displayId")
            } catch (e: Exception) { err("DisplayManager creation failed: ${e.message}"); return false }
        }

        // Post-creation setup
        try { setDisplayImePolicy(displayId) } catch (_: Exception) {}
        try { execShell("settings put global force_resizable_activities 1") } catch (_: Exception) {}
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings get system screen_off_timeout"))
            savedScreenOffTimeout = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            execShell("settings put system screen_off_timeout 2147483647")
            log("Screen timeout disabled (was ${savedScreenOffTimeout}ms)")
        } catch (_: Exception) {}
        try {
            savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")?.trim() ?: "1"
            savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")?.trim() ?: "1"
            execShell("settings put system lift_wakeup_enabled 0")
            execShell("settings put system proximity_wakeup_enabled 0")
            log("Lift/proximity wake disabled")
        } catch (_: Exception) {}
        return displayId >= 0
    }

    // ── Phase 2: Connection ──

    data class ConnectionSet(val carVideo: SocketChannel, val carInput: SocketChannel, val phoneChannel: SocketChannel)

    private fun bindAndAccept(): ConnectionSet? {
        val videoServer: ServerSocketChannel
        val inputServer: ServerSocketChannel
        try {
            videoServer = ServerSocketChannel.open(); videoServer.configureBlocking(false)
            videoServer.socket().reuseAddress = true; videoServer.socket().bind(InetSocketAddress("0.0.0.0", VIDEO_PORT))
            log("Video server bound on 0.0.0.0:$VIDEO_PORT")
            inputServer = ServerSocketChannel.open(); inputServer.configureBlocking(false)
            inputServer.socket().reuseAddress = true; inputServer.socket().bind(InetSocketAddress("0.0.0.0", INPUT_PORT))
            log("Input server bound on 0.0.0.0:$INPUT_PORT")
        } catch (e: Exception) { err("Bind failed: ${e.message}"); return null }

        val phoneChannel = connectToPhoneHost() ?: run {
            try { videoServer.close() } catch (_: Exception) {}
            try { inputServer.close() } catch (_: Exception) {}
            return null
        }

        try { sendDisplayReady(phoneChannel); log("Display ready sent to phone") }
        catch (e: Exception) { err("Display ready failed: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }

        lifecycleChannel = phoneChannel

        execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME")
        log("Home launched on display $displayId")
        setPhysicalDisplayPower(false)
        lastPowerOffTime = System.currentTimeMillis()

        val carVideo = acceptCarChannel(videoServer, "video", 30000) ?: run { err("Car did not connect video"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
        val carInput = acceptCarChannel(inputServer, "input", 30000) ?: run { err("Car did not connect input"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
        try { videoServer.close() } catch (_: Exception) {}
        try { inputServer.close() } catch (_: Exception) {}
        log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")
        return ConnectionSet(carVideo, carInput, phoneChannel)
    }

    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, LIFECYCLE_PORT)
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                log("Connecting to phone lifecycle channel on $phoneHost:$LIFECYCLE_PORT (attempt ${attempt + 1})...")
                ch = SocketChannel.open(); ch.configureBlocking(false); ch.connect(addr)
                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) { if (!running || System.currentTimeMillis() > deadline) { ch.close(); throw ConnectException("timeout") }; Thread.sleep(50) }
                ch.configureBlocking(false); ch.socket().tcpNoDelay = true
                log("Connected to phone lifecycle channel")
                return ch
            } catch (e: ConnectException) { ch?.close(); if (attempt < 59) Thread.sleep(200) }
            catch (e: Exception) { ch?.close(); err("Lifecycle connection error: ${e.message}"); break }
        }
        return null
    }

    private fun sendDisplayReady(ch: SocketChannel) {
        val hasInjection = checkDirectInjectionWorks()
        val flags: Byte = if (hasInjection) 1 else 0
        val buf = ByteBuffer.allocate(6); buf.put(MSG_DISPLAY_READY); buf.putInt(displayId); buf.put(flags); buf.flip()
        ch.configureBlocking(true)
        while (buf.hasRemaining()) ch.write(buf)
        ch.configureBlocking(false)
        log("Display ready sent: id=$displayId ${displayWidth}x${displayHeight}@$dpi injectInput=$hasInjection")
    }

    private fun acceptCarChannel(server: ServerSocketChannel, name: String, timeoutMs: Int): SocketChannel? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val accepted = server.accept()
            if (accepted != null) {
                accepted.configureBlocking(false)
                val sock = accepted.socket(); sock.sendBufferSize = 262144; sock.receiveBufferSize = 262144; sock.tcpNoDelay = true
                return accepted
            }
            Thread.sleep(50)
        }
        return null
    }

    private fun checkDirectInjectionWorks(): Boolean = inputManager != null && injectInputEventMethod != null

    // ── Phase 3: Single-Threaded Pipeline ──

    private fun runPipeline(carVideo: SocketChannel) {
        try { pipelineLoop(carVideo) } catch (e: Exception) { err("Pipeline crashed: ${e.message}"); e.printStackTrace() }
        log("Pipeline thread exited")
    }

    private fun pipelineLoop(carVideo: SocketChannel) {
        val enc = encoder ?: return
        val bufInfo = MediaCodec.BufferInfo()
        var nextFrameNanos = System.nanoTime()
        var frameCount = 0L; var keyFrameCount = 0L; var lastLogAt = 0L
        var bitrate = BITRATE
        var cleanSinceNanos = 0L
        val writeTimeoutNs = 5_000_000_000L

        // GL state from createVirtualDisplay
        val display = eglDisplay ?: return
        val surf = eglSurface ?: return
        val st = stTexture ?: return
        val texId = stTexId
        val prog = glProgram; val posL = glPosLoc; val texL = glTexLoc
        val qBuf = quadBuf ?: return

        // Frame available listener
        val frameLock = Any()
        val frameAvailable = booleanArrayOf(false)
        val cbThread = android.os.HandlerThread("PipeCB").apply { start() }
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameAvailable[0] = true; (frameLock as java.lang.Object).notifyAll() }
        }, android.os.Handler(cbThread.looper))

        log("Pipeline: ${encodeWidth}x${encodeHeight} ${fps}fps ${bitrate / 1_000_000}Mbps")

        while (running) {
            // Step 1: Frame clock — precise, drift-free
            val waitNanos = nextFrameNanos - System.nanoTime()
            if (waitNanos > 0) LockSupport.parkNanos(waitNanos)
            nextFrameNanos += frameIntervalNanos
            if (nextFrameNanos <= System.nanoTime()) nextFrameNanos = System.nanoTime() + frameIntervalNanos

            // Step 2: Grab latest VD frame (non-blocking)
            val hasNew: Boolean
            synchronized(frameLock) { hasNew = frameAvailable[0]; frameAvailable[0] = false }
            if (hasNew) st.updateTexImage()

            // Step 3: GL render (2 triangles, negligible cost)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            qBuf.position(0); GLES20.glVertexAttribPointer(posL, 2, GLES20.GL_FLOAT, false, 16, qBuf); GLES20.glEnableVertexAttribArray(posL)
            qBuf.position(2); GLES20.glVertexAttribPointer(texL, 2, GLES20.GL_FLOAT, false, 16, qBuf); GLES20.glEnableVertexAttribArray(texL)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, surf)

            // Step 4: Drain all encoder output, write to TCP
            var drained = 0
            while (true) {
                val idx = enc.dequeueOutputBuffer(bufInfo, 0)
                if (idx < 0) break
                if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && bufInfo.size > 0) {
                        val payload = ByteArray(bufInfo.size); buf.get(payload)
                        val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val msgType = if (isConfig) VideoMsg.CONFIG else VideoMsg.FRAME
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keyFrameCount++

                        // TCP write + backpressure detection
                        val writeStart = System.nanoTime()
                        writeFrame(carVideo, msgType, payload, writeTimeoutNs)
                        val writeMs = (System.nanoTime() - writeStart) / 1_000_000

                        // Adaptive bitrate
                        if (writeMs > 15) {
                            cleanSinceNanos = 0L
                            val newRate = maxOf(2_000_000, (bitrate * 0.75f).toInt())
                            if (newRate < bitrate) { bitrate = newRate; applyBitrate(enc, bitrate); requestSyncFrame(enc); log("Bitrate DOWN to ${bitrate / 1_000_000}Mbps") }
                        } else {
                            if (cleanSinceNanos == 0L) cleanSinceNanos = System.nanoTime()
                        }
                        drained++; frameCount++
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }

            // Try bitrate upgrade
            if (cleanSinceNanos > 0L && (System.nanoTime() - cleanSinceNanos) / 1_000_000 >= 5000L) {
                val newRate = minOf(BITRATE, bitrate + 1_000_000)
                if (newRate > bitrate) { bitrate = newRate; applyBitrate(enc, bitrate); log("Bitrate UP to ${bitrate / 1_000_000}Mbps") }
                cleanSinceNanos = System.nanoTime()
            }

            if (frameCount - lastLogAt >= 120) { lastLogAt = frameCount; log("Pipeline: $frameCount frames ${bitrate / 1_000_000}Mbps keys=$keyFrameCount") }
        }

        cbThread.quitSafely()
        log("Pipeline exited: $frameCount frames $keyFrameCount keyframes")
    }

    private fun writeFrame(channel: SocketChannel, msgType: Byte, payload: ByteArray, timeoutNs: Long) {
        val frameLen = 2 + payload.size
        val header = byteArrayOf((frameLen shr 24).toByte(), (frameLen shr 16).toByte(), (frameLen shr 8).toByte(), frameLen.toByte(), Channel.VIDEO, msgType)
        writeAll(channel, ByteBuffer.wrap(header), timeoutNs)
        if (payload.isNotEmpty()) writeAll(channel, ByteBuffer.wrap(payload), timeoutNs)
    }

    private fun writeAll(channel: SocketChannel, buf: ByteBuffer, timeoutNs: Long) {
        var deadline = System.nanoTime() + timeoutNs
        while (buf.hasRemaining()) {
            val n = channel.write(buf)
            if (n > 0) deadline = System.nanoTime() + timeoutNs
            else { if (System.nanoTime() > deadline) throw IOException("Write timeout: ${buf.remaining()} bytes after ${timeoutNs / 1_000_000_000}s"); LockSupport.parkNanos(100_000) }
        }
    }

    private fun applyBitrate(enc: MediaCodec, bitrate: Int) {
        try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate); enc.setParameters(p) } catch (_: Exception) {}
    }

    private fun requestSyncFrame(enc: MediaCodec) {
        try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(p) } catch (_: Exception) {}
    }

    // ── Phase 4: Lifecycle + Touch Readers ──

    private fun startLifecycleReader(ch: SocketChannel) {
        Thread({
            try { readLifecycleCommands(ch) } catch (e: Exception) { if (running) err("Lifecycle: ${e.message}") }
        }, "Lifecycle").apply { isDaemon = true }.start()
    }

    private fun readLifecycleCommands(ch: SocketChannel) {
        val reader = NioReader(ch, 4096, frameIntervalNanos / 1_000_000)
        log("Lifecycle reader started")
        try {
            while (running) {
                val cmd = reader.readByteBlocking().toInt() and 0xFF
                if (cmd == CMD_STOP) { log("Received CMD_STOP"); running = false }
                else err("Unknown lifecycle cmd: 0x${cmd.toString(16)}")
            }
        } catch (e: IOException) { if (running) err("Lifecycle reader error: ${e.message}") }
        finally { reader.close(); try { ch.close() } catch (_: Exception) {} }
    }

    private fun startTouchReader(carInput: SocketChannel) {
        Thread({
            log("TouchReader started")
            try { readTouchAndCommands(carInput) } catch (e: Exception) { err("TouchReader: ${e.javaClass.simpleName}: ${e.message}") }
            log("TouchReader exited")
        }, "TouchReader").apply { isDaemon = true }.start()
    }

    private fun readTouchAndCommands(ch: SocketChannel) {
        val reader = NioReader(ch, 65536, frameIntervalNanos / 1_000_000)
        log("Touch/Command reader started")
        while (running) {
            val frame = try { FrameCodec.readFrameBlocking(reader) } catch (e: Exception) { err("Touch read: ${e.message}"); null }
            if (frame == null) { log("Touch channel closed"); break }
            when (frame.channel) {
                Channel.INPUT -> handleTouchFrame(frame)
                Channel.CONTROL -> handleCarCommand(frame)
                else -> err("Unknown channel on input: ${frame.channel}")
            }
        }
        reader.close()
    }

    // ── Touch injection (ported) ──

    private fun handleTouchFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            InputMsg.TOUCH_MOVE_BATCH -> {
                val batch = TouchMoveBatch.decode(frame.payload)
                for (p in batch.pointers) injectTouch(1, p.pointerId, (p.x * displayWidth).toInt(), (p.y * displayHeight).toInt(), p.pressure)
            }
            InputMsg.TOUCH_DOWN, InputMsg.TOUCH_MOVE, InputMsg.TOUCH_UP -> {
                val event = TouchEvent.decode(frame.payload)
                val action = when (frame.messageType) { InputMsg.TOUCH_DOWN -> 0; InputMsg.TOUCH_MOVE -> 1; else -> 2 }
                injectTouch(action, event.pointerId, (event.x * displayWidth).toInt(), (event.y * displayHeight).toInt(), event.pressure)
            }
        }
    }

    private fun injectTouch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float) {
        if (inputManager == null || injectInputEventMethod == null) { injectTouchFallback(action, x, y); return }
        try {
            if (action == 2) { activePointers.remove(pointerId) } else { activePointers[pointerId] = floatArrayOf(x.toFloat(), y.toFloat(), pressure) }
            if (activePointers.isEmpty()) return

            val now = SystemClock.uptimeMillis()
            val pointers = activePointers.entries.toList()
            val propArray = propsPool.copyOf(pointers.size)
            val coordArray = coordsPool.copyOf(pointers.size)
            for ((i, entry) in pointers.withIndex()) {
                val k = entry.key; val v = entry.value
                propArray[i] = (propArray[i] ?: MotionEvent.PointerProperties()).also { it.id = k; it.toolType = MotionEvent.TOOL_TYPE_FINGER }
                coordArray[i] = (coordArray[i] ?: MotionEvent.PointerCoords()).also { it.x = v[0]; it.y = v[1]; it.pressure = v[2]; it.size = 1f }
            }

            val motionAction: Int
            if (action == 0) {
                touchDownTime = now
                motionAction = if (pointers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (pointers.indexOfFirst { it.key == pointerId } shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else if (action == 2) {
                motionAction = if (pointers.isNotEmpty()) MotionEvent.ACTION_UP else return
                if (pointers.size > 1) return  // Multi-pointer up: handled by single up events
            } else {
                motionAction = MotionEvent.ACTION_MOVE
            }

            val event = MotionEvent.obtain(touchDownTime, now, motionAction, pointers.size, propArray, coordArray, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.invoke(event, displayId)
            injectInputEventMethod!!.invoke(inputManager, event, 0)
            event.recycle()

            if (running && System.currentTimeMillis() - lastPowerOffTime > 1000) {
                lastPowerOffTime = System.currentTimeMillis()
                Thread({ setPhysicalDisplayPower(false) }, "PowerOff").start()
            }
        } catch (e: Exception) { /* fall through to fallback */ }
    }

    private fun injectTouchFallback(action: Int, x: Int, y: Int) {
        try {
            when (action) {
                0 -> execFast("input -d $displayId tap $x $y")
                2 -> execFast("input -d $displayId tap $x $y")
                else -> {} // MOVE ignored in fallback
            }
        } catch (_: Exception) {}
    }

    // ── Car commands (ported) ──

    private fun handleCarCommand(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.LAUNCH_APP -> { val pkg = LaunchAppMessage.decode(frame.payload).packageName; launchApp(pkg) }
            ControlMsg.GO_BACK -> { execFast("input -d $displayId keyevent 4"); checkStackEmpty() }
            ControlMsg.GO_HOME -> log("Home: no-op")
            ControlMsg.APP_UNINSTALL -> { val pkg = String(frame.payload, Charsets.UTF_8); execShell("pm uninstall $pkg") }
            ControlMsg.APP_INFO -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                val settings = execShellOutput("cmd package resolve-activity --brief -a android.settings.APPLICATION_DETAILS_SETTINGS com.android.settings")?.trim()
                if (!settings.isNullOrEmpty()) execShell("am start --display $displayId -n $settings -d \"package:$pkg\"")
                else execShell("am start --display $displayId -a android.settings.APPLICATION_DETAILS_SETTINGS -d \"package:$pkg\"")
            }
            ControlMsg.APP_SHORTCUTS -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                val output = execShellOutput("cmd shortcut get-shortcuts --package $pkg 2>/dev/null") ?: ""
                if (output.isNotBlank()) sendShortcutResult(pkg, output)
            }
            ControlMsg.APP_SHORTCUT_ACTION -> { /* handled in a separate method if needed */ }
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName 2>/dev/null | tail -1"))
            val component = p.inputStream.bufferedReader().readText().trim(); p.waitFor()
            if (component.isNotEmpty()) { log("Launching: am start --display $displayId -n $component"); execShell("am start --display $displayId -n $component") }
            else { log("Launching: am start --display $displayId $packageName"); execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName") }
        } catch (e: Exception) { err("launchApp failed: ${e.message}") }
    }

    // ── Stack check ──

    private fun checkStackEmpty() { Thread({ checkStackEmptyImpl() }, "StackCheck").start() }

    private fun checkStackEmptyImpl() {
        try {
            Thread.sleep(300)
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys activity activities 2>/dev/null"))
            val content = p.inputStream.bufferedReader().readText(); p.waitFor()
            val marker = "Display #$displayId "; val start = content.indexOf(marker)
            if (start < 0) { enqueueLifecycleResponse(MSG_STACK_EMPTY, ByteArray(0)); return }
            val nextDisplay = content.indexOf("Display #", start + marker.length)
            val section = if (nextDisplay >= 0) content.substring(start, nextDisplay) else content.substring(start)
            val taskCount = section.lines().count { it.contains("Task{") }
            if (taskCount == 0) { log("Stack empty"); enqueueLifecycleResponse(MSG_STACK_EMPTY, ByteArray(0)) }
            else {
                val focusedPkg = extractFocusedPackage(section)
                if (focusedPkg != null) { log("Focused: $focusedPkg"); enqueueLifecycleResponse(MSG_FOCUSED_APP, focusedPkg.toByteArray(Charsets.UTF_8)) }
                else { log("No focused app — treating as empty"); enqueueLifecycleResponse(MSG_STACK_EMPTY, ByteArray(0)) }
            }
        } catch (e: Exception) { err("checkStackEmpty: ${e.message}") }
    }

    private fun extractFocusedPackage(section: String): String? {
        Regex("topResumedActivity=ActivityRecord\\{[^}]*\\s+(\\S+)/").find(section)?.let { return it.groupValues[1] }
        return null
    }

    // ── Inline lifecycle writes (thread-safe: only called from TouchReader or StackCheck) ──

    private var lifecycleChannel: SocketChannel? = null

    private fun enqueueLifecycleResponse(msgType: Byte, payload: ByteArray) {
        try {
            val ch = lifecycleChannel
            if (ch != null && ch.isOpen) {
                synchronized(ch) {
                    val len = if (payload.isEmpty()) 1 else 5 + payload.size
                    val buf = ByteBuffer.allocate(len); buf.put(msgType)
                    if (payload.isNotEmpty()) { buf.putInt(payload.size); buf.put(payload) }
                    buf.flip()
                    ch.configureBlocking(true)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.configureBlocking(false)
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendShortcutResult(pkg: String, data: String) {
        try {
            val ch = lifecycleChannel
            if (ch != null && ch.isOpen) {
                val pkgBytes = pkg.toByteArray(Charsets.UTF_8); val dataBytes = data.toByteArray(Charsets.UTF_8)
                val buf = ByteBuffer.allocate(1 + 4 + pkgBytes.size + 4 + dataBytes.size)
                buf.put(MSG_SHORTCUTS_RESULT); buf.putInt(pkgBytes.size); buf.put(pkgBytes); buf.putInt(dataBytes.size); buf.put(dataBytes)
                buf.flip()
                synchronized(ch) { ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false) }
            }
        } catch (_: Exception) {}
    }

    // ── Shell helpers ──

    private fun execFast(command: String) {
        try { val si = shellInput; if (si != null) { si.write("$command\n".toByteArray()); si.flush(); return } } catch (_: Exception) {}
    }

    private fun execShell(command: String) {
        try { val si = shellInput; if (si != null) { si.write("$command\n".toByteArray()); si.flush() } } catch (_: Exception) {}
    }

    private fun execShellOutput(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(); out
        } catch (e: Exception) { err("execShellOutput: ${e.message}"); null }
    }

    // ── Display power ──

    private fun setPhysicalDisplayPower(on: Boolean) {
        try {
            if (!displayControlLoaded) { loadDisplayControl() }
            val cls = displayControlClass
            if (cls != null) {
                val getIds = cls.getDeclaredMethod("getPhysicalDisplayIds"); getIds.isAccessible = true
                val ids = getIds.invoke(null) as LongArray
                val setPower = cls.getDeclaredMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                setPower.isAccessible = true
                val tokenClass = Class.forName("android.hardware.display.DisplayManagerInternal\$DisplayPowerRequest")
                for (id in ids) {
                    val token = cls.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as android.os.IBinder
                    setPower.invoke(null, token, if (on) 2 else 0)
                }
                log("Physical display power via SurfaceControl: ${if (on) "ON" else "OFF"}")
                return
            }
        } catch (e: Exception) { err("SurfaceControl power failed: ${e.message}") }
        try { execShell("cmd display power-${if (on) "on" else "off"} 0") } catch (_: Exception) {}
    }

    @Synchronized
    private fun loadDisplayControl() {
        if (displayControlLoaded) return
        try { displayControlClass = Class.forName("com.android.server.display.DisplayControl"); displayControlLoaded = true }
        catch (_: Exception) {
            try {
                val clf = Class.forName("dalvik.system.DelegateLastClassLoader").getDeclaredConstructor(String::class.java, String::class.java, ClassLoader::class.java)
                clf.isAccessible = true
                val loader = clf.newInstance("/system/framework/services.jar", null, ClassLoader.getSystemClassLoader()) as ClassLoader
                displayControlClass = loader.loadClass("com.android.server.display.DisplayControl"); displayControlLoaded = true
            } catch (e: Exception) { err("DisplayControl load failed: ${e.message}") }
        }
    }

    // ── IME policy ──

    private fun setDisplayImePolicy(displayId: Int) {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val wm = sm.getDeclaredMethod("getService", String::class.java).invoke(null, "window") as android.os.IBinder
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val iwm = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, wm)
            iwm.javaClass.getDeclaredMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(iwm, displayId, 0)
            log("IME policy set to LOCAL for display $displayId")
        } catch (_: Exception) {}
    }

    // ── Cleanup ──

    private fun cleanup() {
        running = false
        persistentShell?.let { try { shellInput?.close() } catch (_: Exception) {}; it.destroy(); persistentShell = null; shellInput = null }
        savedScreenOffTimeout?.let { if (it != "2147483647") execShell("settings put system screen_off_timeout $it") }
        savedLiftWakeup?.let { execShell("settings put system lift_wakeup_enabled $it") }
        savedProximityWakeup?.let { execShell("settings put system proximity_wakeup_enabled $it") }
        setPhysicalDisplayPower(true); try { execShell("input keyevent 224") } catch (_: Exception) {}
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {}; encoder = null }
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {}; virtualDisplay = null }
        vdInputSurface?.release()
        stTexture?.release()
        eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglDisplay?.let { EGL14.eglTerminate(it) }
        log("Cleanup complete")
    }

    // ── GL program ──

    private fun createProgram(): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}")
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external:require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main(){gl_FragColor=texture2D(sTexture,vTexCoord);}")
        return GLES20.glCreateProgram().also { prog -> GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs); GLES20.glLinkProgram(prog) }
    }

    private fun loadShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, source); GLES20.glCompileShader(it) }
}
