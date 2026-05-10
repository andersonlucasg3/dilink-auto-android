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

class PipelineServer(
    private val displayWidth: Int, private val displayHeight: Int, private val dpi: Int,
    private val phoneHost: String, private val encodeWidth: Int, private val encodeHeight: Int,
    private val fps: Int
) {
    private val frameIntervalNanos = 1_000_000_000L / fps
    @Volatile private var running = true
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private var touchDownTime = 0L

    private var persistentShell: Process? = null
    private var shellInput: OutputStream? = null
    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null
    private var lastPowerOffTime = 0L

    // SurfaceTexture + VD surface (created on pipeline thread, used by VD)
    private var vdInputSurface: Surface? = null
    private var stTexture: android.graphics.SurfaceTexture? = null
    private var encoderSurface: Surface? = null
    private var lifecycleChannel: SocketChannel? = null

    // EGL/GL resources (created and used exclusively on pipeline thread)
    private var stTexId = 0
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var glProgram = 0
    private var glPosLoc = 0
    private var glTexLoc = 0
    private var quadBuf: FloatBuffer? = null

    // Synchronization between main thread and pipeline thread
    private val inputSurfaceReady = CountDownLatch(1)
    @Volatile private var carVideoChannel: SocketChannel? = null

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
            val w = args.getOrNull(0)?.toInt() ?: 1408; val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120; val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w; val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: 30
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f")
            PipelineServer(w, h, d, ph, ew, eh, f).run()
        }
        private fun log(msg: String) { System.out.println("[Pipeline] $msg"); System.out.flush() }
        private fun err(msg: String) { System.err.println("[Pipeline] $msg"); System.err.flush() }
    }

    fun run() {
        initInputManager()
        initPersistentShell()
        try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
        val enc = encoder ?: return
        encoderSurface = enc.createInputSurface()
        enc.start()
        // Start pipeline thread — it initializes EGL/GL and signals when VD input surface is ready
        val pipelineThread = Thread({ runPipeline() }, "Pipeline").apply { start() }
        try { inputSurfaceReady.await() } catch (_: InterruptedException) { return }
        if (!createVirtualDisplay()) { running = false; err("Fatal: failed to create VD"); return }
        val conns = bindAndAccept() ?: run { running = false; return }
        startLifecycleReader(conns.phoneChannel)
        startTouchReader(conns.carInput)
        // Signal pipeline to begin rendering with the car video channel
        carVideoChannel = conns.carVideo
        LockSupport.unpark(pipelineThread)
        try { pipelineThread.join() } catch (_: InterruptedException) {}
        cleanup()
    }

    private fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as android.os.IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            injectInputEventMethod = inputManager!!.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            try { setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (_: Exception) {}
            log("InputManager ready")
        } catch (e: Exception) { err("InputManager: ${e.message}") }
    }

    private fun initPersistentShell() {
        try { persistentShell = Runtime.getRuntime().exec(arrayOf("sh")); shellInput = persistentShell!!.outputStream } catch (e: Exception) { err("Shell: ${e.message}") }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_LATENCY, 0); format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        format.setLong("repeat-previous-frame-after", 500_000L)
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${BITRATE/1_000_000}Mbps Main 30fps")
    }

    // ── VD Creation (ported from working VirtualDisplayServer) ──

    private fun createVirtualDisplay(): Boolean {
        val vdSurf = vdInputSurface ?: return false

        // Try DisplayManagerGlobal
        try {
            log("DisplayManagerGlobal...")
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val cfgClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val bldClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val bldCtor = bldClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            bldCtor.isAccessible = true
            val bld = bldCtor.newInstance("DiLinkAutoVD", displayWidth, displayHeight, dpi)
            bldClass.getDeclaredMethod("setSurface", Surface::class.java).apply { isAccessible = true; invoke(bld, vdSurf) }
            bldClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType).apply { isAccessible = true; invoke(bld, 0x6c49) }
            try { bldClass.getDeclaredMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType).apply { isAccessible = true; invoke(bld, 0) } } catch (_: NoSuchMethodException) {}
            val cfg = bldClass.getDeclaredMethod("build").apply { isAccessible = true }.invoke(bld)
            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val createVd: Method = try {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, android.os.Handler::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, String::class.java)
            }
            createVd.isAccessible = true
            virtualDisplay = (if (createVd.parameterCount == 4) createVd.invoke(dmg, cfg, null, null, "com.android.shell")
                else createVd.invoke(dmg, cfg, null, "com.android.shell")) as? VirtualDisplay
            if (virtualDisplay != null) {
                try { displayId = virtualDisplay!!.display.displayId } catch (_: Exception) { displayId = findDisplayId("DiLinkAutoVD") }
                log("VD via DisplayManagerGlobal: id=$displayId")
            }
        } catch (e: Exception) { err("DisplayManagerGlobal: ${e.message}") }

        // Fallback: DisplayManager
        if (virtualDisplay == null) {
            try {
                log("DisplayManager...")
                val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
                ctor.isAccessible = true; val dm = ctor.newInstance(FakeContext.get())
                try { DisplayManager::class.java.getDeclaredField("mDisplayIdToMirror").apply { isAccessible = true; setInt(dm, 0) } } catch (_: Exception) {}
                val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 14))
                virtualDisplay = dm.createVirtualDisplay("DiLinkAutoVD", displayWidth, displayHeight, dpi, vdSurf, flags)
                try { displayId = virtualDisplay!!.display.displayId } catch (_: Exception) { displayId = findDisplayId("DiLinkAutoVD") }
                log("VD via DisplayManager: id=$displayId")
            } catch (e: Exception) { err("DisplayManager: ${e.message}") }
        }

        if (displayId < 0) { err("Failed to create VD"); return false }

        try { setDisplayImePolicy(displayId) } catch (_: Exception) {}
        try { execShell("settings put global force_resizable_activities 1") } catch (_: Exception) {}
        try {
            savedScreenOffTimeout = execShellOutput("settings get system screen_off_timeout")?.trim() ?: "60000"
            execShell("settings put system screen_off_timeout 2147483647"); log("Screen timeout disabled")
            savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")?.trim() ?: "1"
            savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")?.trim() ?: "1"
            execShell("settings put system lift_wakeup_enabled 0"); execShell("settings put system proximity_wakeup_enabled 0")
        } catch (_: Exception) {}
        return true
    }

    private fun findDisplayId(name: String): Int {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys display 2>/dev/null | grep -A 5 '$name' | grep 'mDisplayId=' | head -1"))
            val out = p.inputStream.bufferedReader().readText().trim(); p.waitFor()
            return Regex("mDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } catch (_: Exception) { return -1 }
    }

    // ── Connection ──

    data class ConnectionSet(val carVideo: SocketChannel, val carInput: SocketChannel, val phoneChannel: SocketChannel)

    private fun bindAndAccept(): ConnectionSet? {
        val videoServer: ServerSocketChannel; val inputServer: ServerSocketChannel
        try {
            videoServer = ServerSocketChannel.open(); videoServer.configureBlocking(false); videoServer.socket().reuseAddress = true
            videoServer.socket().bind(InetSocketAddress("0.0.0.0", VIDEO_PORT)); log("Video on :$VIDEO_PORT")
            inputServer = ServerSocketChannel.open(); inputServer.configureBlocking(false); inputServer.socket().reuseAddress = true
            inputServer.socket().bind(InetSocketAddress("0.0.0.0", INPUT_PORT)); log("Input on :$INPUT_PORT")
        } catch (e: Exception) { err("Bind: ${e.message}"); return null }
        val phoneChannel = connectToPhoneHost() ?: run { try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        try { sendDisplayReady(phoneChannel); log("Display ready sent") } catch (e: Exception) { err("Display ready: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        lifecycleChannel = phoneChannel
        execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"); log("Home launched")
        setPhysicalDisplayPower(false); lastPowerOffTime = System.currentTimeMillis()
        val carVideo = acceptCarChannel(videoServer, "video", 30000) ?: run { err("Car video timeout"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
        val carInput = acceptCarChannel(inputServer, "input", 30000) ?: run { err("Car input timeout"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
        try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}
        log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")
        return ConnectionSet(carVideo, carInput, phoneChannel)
    }

    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, LIFECYCLE_PORT)
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                ch = SocketChannel.open(); ch.configureBlocking(false); ch.connect(addr)
                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) { if (!running || System.currentTimeMillis() > deadline) { ch.close(); throw ConnectException("timeout") }; Thread.sleep(50) }
                ch.configureBlocking(false); ch.socket().tcpNoDelay = true; return ch
            } catch (e: ConnectException) { ch?.close(); if (attempt < 59) Thread.sleep(200) }
            catch (e: Exception) { ch?.close(); err("Lifecycle connect: ${e.message}"); break }
        }
        return null
    }

    private fun sendDisplayReady(ch: SocketChannel) {
        val hasInjection = inputManager != null && injectInputEventMethod != null
        val buf = ByteBuffer.allocate(6); buf.put(MSG_DISPLAY_READY); buf.putInt(displayId); buf.put(if (hasInjection) 1 else 0); buf.flip()
        ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false)
    }

    private fun acceptCarChannel(server: ServerSocketChannel, name: String, timeoutMs: Int): SocketChannel? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) { val a = server.accept(); if (a != null) { a.configureBlocking(false); val s = a.socket(); s.sendBufferSize = 262144; s.receiveBufferSize = 262144; s.tcpNoDelay = true; return a }; Thread.sleep(50) }
        return null
    }

    // ── Single-Threaded Pipeline (EGL/GL init + render loop, all on pipeline thread) ──

    private fun runPipeline() {
        try {
            initEglAndSurfaceTexture()
            inputSurfaceReady.countDown()
            // Wait for main thread to create VD, accept connections, and set carVideoChannel
            while (carVideoChannel == null && running) LockSupport.park()
            if (!running) return
            pipelineLoop(carVideoChannel!!)
        } catch (e: Exception) { err("Pipeline: ${e.message}"); e.printStackTrace() }
        log("Pipeline exited")
    }

    private fun initEglAndSurfaceTexture() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(display, ver, 0, ver, 1)
        val cfgA = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val nc = IntArray(1)
        EGL14.eglChooseConfig(display, cfgA, 0, cfgs, 0, 1, nc, 0)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreateWindowSurface(display, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surf, surf, ctx)
        eglDisplay = display; eglContext = ctx; eglSurface = surf

        // GL program
        glProgram = createProgram(); GLES20.glUseProgram(glProgram)
        glPosLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        glTexLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        GLES20.glViewport(0, 0, encodeWidth, encodeHeight)

        // Create GL texture for SurfaceTexture (VD input)
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

        log("EGL/GL ready, VD input surface created")
    }

    private fun pipelineLoop(carVideo: SocketChannel) {
        val enc = encoder ?: return
        val st = stTexture ?: return
        val bufInfo = MediaCodec.BufferInfo()

        // Frame sync
        val frameLock = Any(); val frameAvail = booleanArrayOf(false)
        val cbThread = android.os.HandlerThread("PipeCB").apply { start() }
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameAvail[0] = true; (frameLock as java.lang.Object).notifyAll() }
        }, android.os.Handler(cbThread.looper))

        var nextFrameNanos = System.nanoTime()
        var frameCount = 0L; var keyFrameCount = 0L; var lastLogAt = 0L
        var bitrate = BITRATE; var cleanSinceNanos = 0L
        log("Pipeline: ${encodeWidth}x${encodeHeight} ${fps}fps ${bitrate/1_000_000}Mbps")

        while (running) {
            val waitNs = nextFrameNanos - System.nanoTime()
            if (waitNs > 0) LockSupport.parkNanos(waitNs)
            nextFrameNanos += frameIntervalNanos
            if (nextFrameNanos <= System.nanoTime()) nextFrameNanos = System.nanoTime() + frameIntervalNanos

            val hasNew: Boolean; synchronized(frameLock) { hasNew = frameAvail[0]; frameAvail[0] = false }
            if (hasNew) st.updateTexImage()

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
            val qb = quadBuf!!; qb.position(0)
            GLES20.glVertexAttribPointer(glPosLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glPosLoc)
            qb.position(2); GLES20.glVertexAttribPointer(glTexLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glTexLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

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
                        val ws = System.nanoTime()
                        writeFrame(carVideo, msgType, payload)
                        val wm = (System.nanoTime() - ws) / 1_000_000
                        if (wm > 15) { cleanSinceNanos = 0L; val nr = maxOf(2_000_000, (bitrate * 0.75f).toInt()); if (nr < bitrate) { bitrate = nr; applyBitrate(enc, bitrate); requestSyncFrame(enc) } }
                        else if (cleanSinceNanos == 0L) cleanSinceNanos = System.nanoTime()
                        drained++; frameCount++
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
            if (cleanSinceNanos > 0L && (System.nanoTime() - cleanSinceNanos) / 1_000_000 >= 5000L) { val nr = minOf(BITRATE, bitrate + 1_000_000); if (nr > bitrate) { bitrate = nr; applyBitrate(enc, bitrate) }; cleanSinceNanos = System.nanoTime() }
            if (frameCount - lastLogAt >= 120) { lastLogAt = frameCount; log("Pipeline: $frameCount frames ${bitrate/1_000_000}Mbps keys=$keyFrameCount") }
        }
        cbThread.quitSafely()
        log("Pipeline exited: $frameCount frames")
    }

    private fun writeFrame(ch: SocketChannel, msgType: Byte, payload: ByteArray) {
        val fl = 2 + payload.size; val hdr = byteArrayOf((fl shr 24).toByte(), (fl shr 16).toByte(), (fl shr 8).toByte(), fl.toByte(), Channel.VIDEO, msgType)
        writeAll(ch, ByteBuffer.wrap(hdr)); if (payload.isNotEmpty()) writeAll(ch, ByteBuffer.wrap(payload))
    }
    private fun writeAll(ch: SocketChannel, buf: ByteBuffer) { var dl = System.nanoTime() + 5_000_000_000L; while (buf.hasRemaining()) { if (ch.write(buf) > 0) dl = System.nanoTime() + 5_000_000_000L; else { if (System.nanoTime() > dl) throw IOException("Write timeout"); LockSupport.parkNanos(100_000) } } }
    private fun applyBitrate(enc: MediaCodec, br: Int) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, br); enc.setParameters(p) } catch (_: Exception) {} }
    private fun requestSyncFrame(enc: MediaCodec) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(p) } catch (_: Exception) {} }

    // ── Lifecycle + Touch ──

    private fun startLifecycleReader(ch: SocketChannel) { Thread({ try { readLifecycleCommands(ch) } catch (e: Exception) { if (running) err("Lifecycle: ${e.message}") } }, "Lifecycle").apply { isDaemon = true }.start() }
    private fun readLifecycleCommands(ch: SocketChannel) { val r = NioReader(ch, 4096, frameIntervalNanos/1_000_000); try { while (running) { if ((r.readByteBlocking().toInt() and 0xFF) == CMD_STOP) { log("CMD_STOP"); running = false } } } catch (e: IOException) { if (running) err("Lifecycle: ${e.message}") } finally { r.close(); try { ch.close() } catch (_: Exception) {} } }

    private fun startTouchReader(carInput: SocketChannel) { Thread({ try { readTouchAndCommands(carInput) } catch (e: Exception) { err("Touch: ${e.message}") } }, "TouchReader").apply { isDaemon = true }.start() }
    private fun readTouchAndCommands(ch: SocketChannel) { val r = NioReader(ch, 65536, frameIntervalNanos/1_000_000); while (running) { val f = try { FrameCodec.readFrameBlocking(r) } catch (e: Exception) { null }; if (f == null) break; when (f.channel) { Channel.INPUT -> handleTouchFrame(f); Channel.CONTROL -> handleCarCommand(f) } }; r.close() }

    private fun handleTouchFrame(f: FrameCodec.Frame) {
        when (f.messageType) {
            InputMsg.TOUCH_MOVE_BATCH -> { val b = TouchMoveBatch.decode(f.payload); for (p in b.pointers) injectTouch(1, p.pointerId, (p.x * displayWidth).toInt(), (p.y * displayHeight).toInt(), p.pressure) }
            InputMsg.TOUCH_DOWN, InputMsg.TOUCH_MOVE, InputMsg.TOUCH_UP -> { val e = TouchEvent.decode(f.payload); injectTouch(when(f.messageType){InputMsg.TOUCH_DOWN->0;InputMsg.TOUCH_MOVE->1;else->2}, e.pointerId, (e.x*displayWidth).toInt(), (e.y*displayHeight).toInt(), e.pressure) }
        }
    }
    private fun injectTouch(action: Int, ptr: Int, x: Int, y: Int, pressure: Float) {
        if (inputManager == null || injectInputEventMethod == null) { try { if (action == 0 || action == 2) execFast("input -d $displayId tap $x $y") } catch (_: Exception) {}; return }
        try {
            if (action == 2) activePointers.remove(ptr) else activePointers[ptr] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
            if (activePointers.isEmpty()) return
            val now = SystemClock.uptimeMillis(); val pts = activePointers.entries.toList()
            for ((i, e) in pts.withIndex()) { val k = e.key; val v = e.value; propsPool[i] = (propsPool[i] ?: MotionEvent.PointerProperties()).also { it.id = k; it.toolType = MotionEvent.TOOL_TYPE_FINGER }; coordsPool[i] = (coordsPool[i] ?: MotionEvent.PointerCoords()).also { it.x = v[0]; it.y = v[1]; it.pressure = v[2]; it.size = 1f } }
            val ma = if (action == 0) { touchDownTime = now; if (pts.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (pts.indexOfFirst { it.key == ptr } shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) }
            else if (action == 2) { if (pts.isEmpty()) MotionEvent.ACTION_UP else return }
            else MotionEvent.ACTION_MOVE
            val ev = MotionEvent.obtain(touchDownTime, now, ma, pts.size, propsPool, coordsPool, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.invoke(ev, displayId); injectInputEventMethod!!.invoke(inputManager, ev, 0); ev.recycle()
            if (running && System.currentTimeMillis() - lastPowerOffTime > 1000) { lastPowerOffTime = System.currentTimeMillis(); Thread({ setPhysicalDisplayPower(false) }, "PowerOff").start() }
        } catch (_: Exception) {}
    }

    private fun handleCarCommand(f: FrameCodec.Frame) {
        when (f.messageType) {
            ControlMsg.LAUNCH_APP -> launchApp(LaunchAppMessage.decode(f.payload).packageName)
            ControlMsg.GO_BACK -> { execFast("input -d $displayId keyevent 4"); checkStackEmpty() }
            ControlMsg.GO_HOME -> {}
            ControlMsg.APP_UNINSTALL -> execShell("pm uninstall ${String(f.payload, Charsets.UTF_8)}")
            ControlMsg.APP_INFO -> { val pkg = String(f.payload, Charsets.UTF_8); val s = execShellOutput("cmd package resolve-activity --brief -a android.settings.APPLICATION_DETAILS_SETTINGS com.android.settings")?.trim(); if (!s.isNullOrEmpty()) execShell("am start --display $displayId -n $s -d \"package:$pkg\"") else execShell("am start --display $displayId -a android.settings.APPLICATION_DETAILS_SETTINGS -d \"package:$pkg\"") }
            ControlMsg.APP_SHORTCUTS -> { val pkg = String(f.payload, Charsets.UTF_8); val o = execShellOutput("cmd shortcut get-shortcuts --package $pkg 2>/dev/null") ?: ""; if (o.isNotBlank()) sendShortcutResult(pkg, o) }
        }
    }
    private fun launchApp(pkg: String) { try { val c = execShellOutput("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg 2>/dev/null | tail -1")?.trim(); if (!c.isNullOrEmpty()) execShell("am start --display $displayId -n $c") else execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg") } catch (e: Exception) { err("launch: ${e.message}") } }

    private fun checkStackEmpty() { Thread({ try { Thread.sleep(300); val d = execShellOutput("dumpsys activity activities 2>/dev/null") ?: ""; val m = "Display #$displayId "; val s = d.indexOf(m); if (s < 0) { enqueueResponse(MSG_STACK_EMPTY, ByteArray(0)) } else { val nd = d.indexOf("Display #", s+m.length); val sec = if (nd >= 0) d.substring(s, nd) else d.substring(s); if (sec.lines().none { it.contains("Task{") }) enqueueResponse(MSG_STACK_EMPTY, ByteArray(0)); else { Regex("topResumedActivity=ActivityRecord\\{[^}]*\\s+(\\S+)/").find(sec)?.let { enqueueResponse(MSG_FOCUSED_APP, it.groupValues[1].toByteArray(Charsets.UTF_8)) } ?: enqueueResponse(MSG_STACK_EMPTY, ByteArray(0)) } } } catch (_: Exception) {} }, "StackCheck").start() }

    private fun enqueueResponse(msgType: Byte, payload: ByteArray) {
        try { val ch = lifecycleChannel; if (ch != null && ch.isOpen) synchronized(ch) { val len = if (payload.isEmpty()) 1 else 5 + payload.size; val buf = ByteBuffer.allocate(len); buf.put(msgType); if (payload.isNotEmpty()) { buf.putInt(payload.size); buf.put(payload) }; buf.flip(); ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false) } } catch (_: Exception) {}
    }
    private fun sendShortcutResult(pkg: String, data: String) { try { val ch = lifecycleChannel; if (ch != null && ch.isOpen) { val pb = pkg.toByteArray(Charsets.UTF_8); val db = data.toByteArray(Charsets.UTF_8); val buf = ByteBuffer.allocate(1+4+pb.size+4+db.size); buf.put(MSG_SHORTCUTS_RESULT); buf.putInt(pb.size); buf.put(pb); buf.putInt(db.size); buf.put(db); buf.flip(); synchronized(ch) { ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false) } } } catch (_: Exception) {} }

    // ── Helpers ──

    private fun execFast(cmd: String) { try { shellInput?.let { it.write("$cmd\n".toByteArray()); it.flush() } } catch (_: Exception) {} }
    private fun execShell(cmd: String) { try { shellInput?.let { it.write("$cmd\n".toByteArray()); it.flush() } } catch (_: Exception) {} }
    private fun execShellOutput(cmd: String): String? = try { val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)); val o = p.inputStream.bufferedReader().readText(); p.waitFor(); o } catch (_: Exception) { null }

    private fun setPhysicalDisplayPower(on: Boolean) {
        try {
            if (!displayControlLoaded) { try { displayControlClass = Class.forName("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { try { val clf = Class.forName("dalvik.system.DelegateLastClassLoader").getDeclaredConstructor(String::class.java, String::class.java, ClassLoader::class.java); clf.isAccessible = true; displayControlClass = (clf.newInstance("/system/framework/services.jar", null, ClassLoader.getSystemClassLoader()) as ClassLoader).loadClass("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { err("DisplayControl load failed") } } }
            val cls = displayControlClass; if (cls != null) { val gid = cls.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }; val ids = gid.invoke(null) as LongArray; val sp = cls.getDeclaredMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }; for (id in ids) { sp.invoke(null, cls.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, id), if (on) 2 else 0) } }
        } catch (_: Exception) { try { execShell("cmd display power-${if (on) "on" else "off"} 0") } catch (_: Exception) {} }
    }
    private fun setDisplayImePolicy(id: Int) { try { val wm = Class.forName("android.view.IWindowManager\$Stub").getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, "window")); wm.javaClass.getDeclaredMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, 0) } catch (_: Exception) {} }

    private fun cleanup() {
        running = false; persistentShell?.let { try { shellInput?.close() } catch (_: Exception) {}; it.destroy() }
        savedScreenOffTimeout?.let { if (it != "2147483647") execShell("settings put system screen_off_timeout $it") }
        savedLiftWakeup?.let { execShell("settings put system lift_wakeup_enabled $it") }
        savedProximityWakeup?.let { execShell("settings put system proximity_wakeup_enabled $it") }
        setPhysicalDisplayPower(true); try { execShell("input keyevent 224") } catch (_: Exception) {}
        // EGL cleanup
        val d = eglDisplay; val s = eglSurface; val c = eglContext
        if (d != null) EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (d != null && s != null) EGL14.eglDestroySurface(d, s)
        if (d != null && c != null) EGL14.eglDestroyContext(d, c)
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }; vdInputSurface?.release(); stTexture?.release()
        log("Cleanup complete")
    }
    private fun createProgram(): Int { val vs = loadShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}"); val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external:require\nprecision mediump float;varying vec2 vTexCoord;uniform samplerExternalOES sTexture;void main(){gl_FragColor=texture2D(sTexture,vTexCoord);}"); return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it) } }
    private fun loadShader(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
}
