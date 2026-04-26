package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.NioReader
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Lightweight server that runs via app_process as shell UID (2000).
 * Creates a real VirtualDisplay, encodes its content as H.264,
 * and streams it over a local TCP socket.
 *
 * Shell UID can create virtual displays that host any activity.
 * Uses FakeContext (com.android.shell identity) for DisplayManager access,
 * following the same approach as scrcpy.
 *
 * Usage: CLASSPATH=<dex> app_process / com.dilinkauto.vdserver.VirtualDisplayServer <width> <height> <dpi> <port>
 */
class VirtualDisplayServer(
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val port: Int,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val fps: Int
) {
    private val frameIntervalMs = 1000L / fps

    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    // NIO write queue — lock-free enqueue from encoder thread, drained by writer thread
    private val writeQueue = ConcurrentLinkedQueue<ByteBuffer>()
    @Volatile private var writerThread: Thread? = null

    private var scaler: SurfaceScaler? = null
    private var scalerThread: Thread? = null
    @Volatile private var running = true

    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null
    private var lastPowerOffTime = 0L

    // Persistent shell for fast input injection (avoids fork+exec per tap/swipe)
    private var persistentShell: Process? = null
    private var shellInput: OutputStream? = null

    // Direct MotionEvent injection via IInputManager (bypasses shell entirely)
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    // Multi-touch pointer state
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private var touchDownTime = 0L

    // Pre-allocated pools for MotionEvent construction — avoids per-touch GC pressure
    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    // ── Main entry point ──

    companion object {
        private const val MSG_VIDEO_CONFIG: Byte = 0x01
        private const val MSG_VIDEO_FRAME: Byte = 0x02
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11

        private const val CMD_LAUNCH_APP = 0x20
        private const val CMD_GO_BACK = 0x21
        private const val CMD_GO_HOME = 0x22
        private const val CMD_INPUT_TAP = 0x30
        private const val CMD_INPUT_SWIPE = 0x31
        private const val CMD_INPUT_TOUCH = 0x32
        private const val CMD_STOP = 0xFF

        private const val BITRATE = 12_000_000
        private const val I_FRAME_INTERVAL = 1
        private const val MAX_POINTERS = 10

        private var displayControlClass: Class<*>? = null
        private var displayControlLoaded = false

        @JvmStatic
        fun main(args: Array<String>) {
            val w = args.getOrNull(0)?.toInt() ?: 1408
            val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120
            val p = args.getOrNull(3)?.toInt() ?: 19637
            val ew = args.getOrNull(4)?.toInt() ?: w
            val eh = args.getOrNull(5)?.toInt() ?: h
            val fps = args.getOrNull(6)?.toInt() ?: 30

            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, port=$p, fps=$fps")
            VirtualDisplayServer(w, h, d, p, ew, eh, fps).run()
        }

        private fun log(msg: String) {
            System.out.println("[VDServer] $msg")
            System.out.flush()
        }

        private fun err(msg: String) {
            System.err.println("[VDServer] $msg")
            System.err.flush()
        }
    }

    private fun log(msg: String) = Companion.log(msg)
    private fun err(msg: String) = Companion.err(msg)

    // ── Main logic ──

    private fun run() {
        // Phase 1: Set up everything BEFORE connecting
        initInputManager()
        initPersistentShell()

        try {
            setupEncoder()
            createVirtualDisplay()
        } catch (e: Exception) {
            err("Fatal: failed to create VD/encoder: ${e.message}")
            e.printStackTrace()
            return
        }

        if (displayId < 0) {
            err("Failed to create virtual display")
            return
        }

        // Phase 2: Connect TO the phone (reverse connection)
        val connected = connectToPhone()
        if (!connected) {
            err("Could not connect to phone after retries — exiting")
        }

        cleanup()
    }

    private fun connectToPhone(): Boolean {
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                log("Connecting to phone on localhost:$port (attempt ${attempt + 1})...")
                ch = SocketChannel.open()
                ch.configureBlocking(false)
                ch.connect(InetSocketAddress("127.0.0.1", port))

                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) {
                    if (!running || System.currentTimeMillis() > deadline) {
                        ch.close()
                        throw ConnectException("timeout or stopped")
                    }
                    Thread.sleep(50)
                }

                log("Connected to phone")
                handleClient(ch)
                log("Phone disconnected — exiting")
                return true
            } catch (e: ConnectException) {
                ch?.close()
                Thread.sleep(200)
            } catch (e: Exception) {
                ch?.close()
                err("Connection error: ${e.message}")
                break
            }
        }
        return false
    }

    private fun handleClient(ch: SocketChannel) {
        try {
            ch.configureBlocking(false)
            ch.socket().tcpNoDelay = true
            ch.socket().sendBufferSize = 262144
            ch.socket().receiveBufferSize = 262144

            // Tell client the display is ready (direct write before threads start)
            val readyBuf = ByteBuffer.allocate(5)
            readyBuf.put(MSG_DISPLAY_READY)
            readyBuf.putInt(displayId)
            readyBuf.flip()
            writeAllBlocking(ch, readyBuf)
            log("Display ready: id=$displayId ${width}x${height}@${dpi}")

            // Launch home activity on VD so the encoder has content
            execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME")
            log("Home launched on display $displayId")

            // Power off physical display while streaming
            setPhysicalDisplayPower(false)
            lastPowerOffTime = System.currentTimeMillis()

            // Start NIO writer thread
            val wt = Thread({
                log("Writer thread started")
                try { runWriter(ch) } catch (e: Exception) {
                    err("Writer thread CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                }
                log("Writer thread exited (running=$running)")
            }, "NioWriter").apply { isDaemon = true }
            wt.start()
            writerThread = wt

            // Start video output thread
            val videoThread = Thread({
                log("VideoOutput thread started")
                try { readEncoderOutput() } catch (e: Exception) {
                    err("VideoOutput thread CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
                log("VideoOutput thread exited (running=$running)")
            }, "VideoOutput").apply { isDaemon = true }
            videoThread.start()

            // Read commands from client (NIO Selector-based, uses shared NioReader)
            readCommands(ch)
        } catch (e: IOException) {
            err("Client error: ${e.message}")
        }
    }

    /** Blocking write for initial handshake before NIO threads start */
    private fun writeAllBlocking(ch: SocketChannel, buf: ByteBuffer) {
        ch.configureBlocking(true)
        while (buf.hasRemaining()) ch.write(buf)
        ch.configureBlocking(false)
    }

    /** Enqueue a single-byte message (e.g., MSG_STACK_EMPTY) */
    private fun enqueueWriteByte(msgType: Byte) {
        val buf = ByteBuffer.allocate(1)
        buf.put(msgType)
        buf.flip()
        writeQueue.add(buf)
        val wt = writerThread
        if (wt != null) LockSupport.unpark(wt)
    }

    /** NIO writer thread — drains writeQueue using shared FrameCodec.writeAll */
    private fun runWriter(ch: SocketChannel) {
        var writeCount = 0L
        while (running) {
            val buf = writeQueue.poll()
            if (buf == null) {
                LockSupport.parkNanos(frameIntervalMs * 1_000_000L)
                continue
            }
            FrameCodec.writeAll(ch, buf)
            writeCount++
            if (writeCount % 60 == 0L) {
                log("Writer: wrote $writeCount messages, queue=${writeQueue.size}")
            }
        }
    }

    // ── Encoder ──

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        format.setInteger(MediaFormat.KEY_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time — ensures P-frames between keyframes
        format.setLong("repeat-previous-frame-after", 1_000_000L)

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${BITRATE / 1_000_000}Mbps High low-latency")
        } catch (e: Exception) {
            throw IOException("Failed to create encoder: ${e.message}", e)
        }
    }

    private fun readEncoderOutput() {
        log("readEncoderOutput: entering, encoder=${encoder != null} running=$running")
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var frameCount = 0L
        var keyFrameCount = 0L
        var noOutputCount = 0L
        var lastFrameTime = System.currentTimeMillis()
        var lastKeyFrameAt = 0L

        while (running) {
            try {
                val outputIndex = enc.dequeueOutputBuffer(info, frameIntervalMs * 1000)
                if (outputIndex >= 0) {
                    val now = System.currentTimeMillis()
                    val gap = now - lastFrameTime

                    val buffer = enc.getOutputBuffer(outputIndex)
                    if (buffer == null || info.size <= 0) {
                        noOutputCount++
                        if (noOutputCount == 1L || noOutputCount == 10L || noOutputCount % 100 == 0L) {
                            log("Empty output buffer #$noOutputCount: index=$outputIndex size=${info.size} flags=0x${info.flags.toString(16)} gap=${now - lastFrameTime}ms sent=$frameCount")
                        }
                        enc.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    lastFrameTime = now
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val msgType = if (isConfig) MSG_VIDEO_CONFIG else MSG_VIDEO_FRAME
                    val size = info.size

                    if (isKeyFrame) {
                        keyFrameCount++
                        val sinceLast = if (lastKeyFrameAt > 0) now - lastKeyFrameAt else 0
                        log("KEYFRAME #$keyFrameCount at frame $frameCount size=$size sinceLast=${sinceLast}ms")
                        lastKeyFrameAt = now
                    }

                    // Single allocation: encode header + payload directly into ByteBuffer
                    val buf = ByteBuffer.allocate(1 + 4 + size)
                    buf.put(msgType)
                    buf.putInt(size)
                    buffer.get(buf.array(), buf.arrayOffset() + buf.position(), size)
                    buf.position(buf.limit())  // advance position past payload
                    buf.flip()
                    writeQueue.add(buf)
                    val wt = writerThread
                    if (wt != null) LockSupport.unpark(wt)

                    frameCount++
                    noOutputCount = 0
                    if (frameCount <= 10 || frameCount % 30 == 0L || gap > 1000) {
                        log("Sent $frameCount frames (gap=${gap}ms size=$size flags=0x${info.flags.toString(16)} keys=$keyFrameCount)")
                    }

                    enc.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Encoder output format changed: ${enc.outputFormat}")
                } else {
                    noOutputCount++
                    if (noOutputCount == 1L || noOutputCount == 30L) {
                        log("dequeueOutputBuffer returned $outputIndex (poll #$noOutputCount sent=$frameCount)")
                    }
                    if (noOutputCount == 30L || noOutputCount % 100 == 0L) {
                        val gap = System.currentTimeMillis() - lastFrameTime
                        log("WARNING: encoder stalled ${gap}ms ($noOutputCount polls, sent $frameCount keys=$keyFrameCount)")
                    }
                }
            } catch (e: Exception) {
                err("Video output error: ${e.message}")
                running = false
                break
            }
        }
    }

    // ── Command reader (uses shared NioReader from protocol module) ──

    private fun readCommands(ch: SocketChannel) {
        try {
            val reader = NioReader(ch, 65536, frameIntervalMs)
            log("Command reader started (NioReader)")

            var cmdCount = 0L
            while (running) {
                val cmd = reader.readByteBlocking().toInt() and 0xFF
                when (cmd) {
                    CMD_LAUNCH_APP -> {
                        val len = reader.readIntBlocking()
                        val buf = ByteArray(len)
                        reader.readFullyBlocking(buf, 0, len)
                        launchApp(String(buf))
                    }
                    CMD_GO_BACK -> {
                        execFast("input -d $displayId keyevent 4")
                        checkStackEmpty()
                    }
                    CMD_GO_HOME -> log("Home: no-op (car handles launcher navigation)")
                    CMD_INPUT_TAP -> {
                        val x = reader.readIntBlocking()
                        val y = reader.readIntBlocking()
                        execFast("input -d $displayId tap $x $y")
                    }
                    CMD_INPUT_SWIPE -> {
                        val x1 = reader.readIntBlocking()
                        val y1 = reader.readIntBlocking()
                        val x2 = reader.readIntBlocking()
                        val y2 = reader.readIntBlocking()
                        val dur = reader.readIntBlocking()
                        execFast("input -d $displayId swipe $x1 $y1 $x2 $y2 $dur")
                    }
                    CMD_INPUT_TOUCH -> {
                        val action = reader.readByteBlocking().toInt() and 0xFF
                        val pointerId = reader.readIntBlocking()
                        val tx = reader.readIntBlocking()
                        val ty = reader.readIntBlocking()
                        val pressure = reader.readFloatBlocking()
                        injectTouch(action, pointerId, tx, ty, pressure)
                        cmdCount++
                        if (cmdCount <= 3 || cmdCount % 100 == 0L) {
                            log("Touch cmd #$cmdCount action=$action ptr=$pointerId x=$tx y=$ty")
                        }
                    }
                    CMD_STOP -> running = false
                    else -> err("Unknown command: 0x${cmd.toString(16)}")
                }
            }
            reader.close()
        } catch (e: IOException) {
            if (running) err("Command error: ${e.message}")
        } finally {
            running = false
        }
    }

    // ── App launch ──

    private fun launchApp(packageName: String) {
        val component = resolveActivity(packageName)
        val cmd = if (component != null) {
            "am start --display $displayId -n $component"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
        }
        log("Launching: $cmd")
        execShell(cmd)
    }

    private fun resolveActivity(packageName: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName")
            )
            val buf = ByteArray(1024)
            val len = p.inputStream.read(buf)
            if (len > 0) {
                val output = String(buf, 0, len)
                output.trim().split("\n").lastOrNull { it.contains("/") }?.trim()
            } else null
        } catch (e: Exception) {
            err("resolveActivity failed: ${e.message}")
            null
        }
    }

    // ── Touch injection ──

    private fun injectTouch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float) {
        // Update pointer state
        when (action) {
            0 -> { // DOWN
                activePointers[pointerId] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
                if (activePointers.size == 1) touchDownTime = android.os.SystemClock.uptimeMillis()
            }
            1 -> { // MOVE
                val coords = activePointers[pointerId] ?: return
                coords[0] = x.toFloat(); coords[1] = y.toFloat(); coords[2] = pressure
            }
            // UP: don't remove yet — need pointer in the event
        }

        val im = inputManager
        val injectMethod = injectInputEventMethod
        if (im != null && injectMethod != null) {
            try {
                val pointerCount = activePointers.size
                if (pointerCount == 0) return

                var actionIndex = 0
                var i = 0
                for ((pid, coords) in activePointers) {
                    if (pid == pointerId) actionIndex = i
                    propsPool[i].id = pid
                    propsPool[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                    coordsPool[i].x = coords[0]
                    coordsPool[i].y = coords[1]
                    coordsPool[i].pressure = coords[2]
                    coordsPool[i].size = 1.0f
                    i++
                }

                val motionAction = when (action) {
                    0 -> if (pointerCount == 1) MotionEvent.ACTION_DOWN
                         else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    2 -> if (pointerCount == 1) MotionEvent.ACTION_UP
                         else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    else -> MotionEvent.ACTION_MOVE
                }

                val now = android.os.SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(
                    touchDownTime, now, motionAction,
                    pointerCount, propsPool, coordsPool,
                    0, 0, 1.0f, 1.0f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
                )

                setDisplayIdMethod?.invoke(event, displayId)
                injectMethod.invoke(im, event, 0)
                event.recycle()

                // Touch injection wakes the physical display — re-power-off (throttled)
                val nowMs = System.currentTimeMillis()
                if (running && nowMs - lastPowerOffTime > 1000) {
                    lastPowerOffTime = nowMs
                    Thread({ setPhysicalDisplayPower(false) }, "PowerOff").start()
                }
            } catch (e: Exception) {
                err("MotionEvent injection failed: ${e.message}")
                if (action == 0 || action == 2) execFast("input -d $displayId tap $x $y")
            }
        } else {
            // Fallback: shell commands
            if (action == 0) {
                activePointers[pointerId] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
            } else if (action == 2) {
                val start = activePointers[pointerId]
                if (start != null) {
                    val dx = x - start[0]; val dy = y - start[1]
                    if (Math.sqrt((dx * dx + dy * dy).toDouble()) < 20) {
                        execFast("input -d $displayId tap $x $y")
                    } else {
                        execFast("input -d $displayId swipe ${start[0].toInt()} ${start[1].toInt()} $x $y 200")
                    }
                }
            }
        }

        // Remove pointer after UP and clear all if empty (prevents ghost fingers)
        if (action == 2) {
            activePointers.remove(pointerId)
            if (activePointers.isEmpty()) {
                activePointers.clear()
                touchDownTime = 0
            }
        }
    }

    // ── Shell helpers ──

    private fun execFast(command: String) {
        try {
            val si = shellInput
            if (si != null) {
                si.write("$command\n".toByteArray())
                si.flush()
                return
            }
        } catch (_: Exception) {}
        execShell(command)
    }

    private fun execShellOutput(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val buf = ByteArray(64)
            val len = p.inputStream.read(buf)
            p.waitFor()
            if (len > 0) String(buf, 0, len).trim() else null
        } catch (e: Exception) { null }
    }

    private fun execShell(command: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            p.waitFor()
            if (p.exitValue() != 0) {
                val errBuf = ByteArray(512)
                val len = p.errorStream.read(errBuf)
                if (len > 0) err("Shell failed (${p.exitValue()}): ${String(errBuf, 0, len).trim()}")
            }
        } catch (e: Exception) {
            err("execShell error: ${e.message}")
        }
    }

    // ── Virtual Display creation ──

    private fun createVirtualDisplay() {
        val enc = encoder ?: return
        val encoderSurface = enc.createInputSurface()
        enc.start()

        val vdSurface = if (width != encodeWidth || height != encodeHeight) {
            log("GPU scaling: VD ${width}x${height} → encoder ${encodeWidth}x${encodeHeight}")
            scaler = SurfaceScaler(encoderSurface, width, height, encodeWidth, encodeHeight, frameIntervalMs)
            scaler!!.start()
            scaler!!.getInputSurface() // blocks until ready
        } else encoderSurface

        // Strategy 1: DisplayManagerGlobal
        try {
            log("Trying DisplayManagerGlobal approach...")
            virtualDisplay = createVirtualDisplayViaGlobal(vdSurface)
            if (virtualDisplay != null) {
                displayId = virtualDisplay!!.display.displayId
                log("VirtualDisplay created via DisplayManagerGlobal: displayId=$displayId")
                return
            }
            err("DisplayManagerGlobal returned null VD")
        } catch (e: Exception) {
            err("DisplayManagerGlobal failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }

        // Strategy 2: DisplayManager with mDisplayIdToMirror bypass
        try {
            log("Trying DisplayManager + mDisplayIdToMirror bypass...")
            val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true
            val dm = ctor.newInstance(FakeContext.get())

            try {
                val field = DisplayManager::class.java.getDeclaredField("mDisplayIdToMirror")
                field.isAccessible = true
                field.setInt(dm, 0)
                log("Set mDisplayIdToMirror=0")
            } catch (e: Exception) {
                err("mDisplayIdToMirror reflection failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    or (1 shl 6)   // ROTATES_WITH_CONTENT
                    or (1 shl 9)   // SHOULD_SHOW_SYSTEM_DECORATIONS
                    or (1 shl 10)  // TRUSTED
                    or (1 shl 11)  // OWN_DISPLAY_GROUP
                    or (1 shl 13)  // ALWAYS_UNLOCKED
                    or (1 shl 14)) // OWN_FOCUS

            log("Creating VD with flags=0x${flags.toString(16)}")
            virtualDisplay = dm.createVirtualDisplay("DiLinkAutoVD", width, height, dpi, vdSurface, flags)

            if (virtualDisplay != null) {
                displayId = virtualDisplay!!.display.displayId
                log("VirtualDisplay created via DisplayManager: displayId=$displayId")
                setDisplayImePolicy(displayId)
                execShell("settings put global force_resizable_activities 1")
                log("Enabled force_resizable_activities for VD")

                // Disable screen timeout
                savedScreenOffTimeout = execShellOutput("settings get system screen_off_timeout") ?: "60000"
                execShell("settings put system screen_off_timeout 2147483647")
                log("Screen timeout disabled (was ${savedScreenOffTimeout}ms)")

                // Disable proximity/lift wake
                savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")
                savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")
                execShell("settings put system lift_wakeup_enabled 0")
                execShell("settings put system proximity_wakeup_enabled 0")
                log("Lift/proximity wake disabled (was lift=$savedLiftWakeup prox=$savedProximityWakeup)")

            } else {
                err("DisplayManager.createVirtualDisplay returned null")
            }
        } catch (e: Exception) {
            err("DisplayManager approach failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createVirtualDisplayViaGlobal(surface: Surface): VirtualDisplay? {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val getInstance = dmgClass.getDeclaredMethod("getInstance")
        val dmg = getInstance.invoke(null)
        log("Got DisplayManagerGlobal instance")

        val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or (1 shl 6) or (1 shl 9) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 14))

        val configBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builderCtor = configBuilderClass.getDeclaredConstructor(
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        builderCtor.isAccessible = true
        val builder = builderCtor.newInstance("DiLinkAutoVD", width, height, dpi)

        configBuilderClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType).apply {
            isAccessible = true; invoke(builder, flags)
        }
        configBuilderClass.getDeclaredMethod("setSurface", Surface::class.java).apply {
            isAccessible = true; invoke(builder, surface)
        }
        try {
            configBuilderClass.getDeclaredMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType).apply {
                isAccessible = true; invoke(builder, 0)
            }
            log("VirtualDisplayConfig: displayIdToMirror=0")
        } catch (_: NoSuchMethodException) {
            log("setDisplayIdToMirror not available (older API)")
        }

        val build = configBuilderClass.getDeclaredMethod("build")
        build.isAccessible = true
        val config = build.invoke(builder)
        log("VirtualDisplayConfig built")

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val createVD: Method = try {
            dmgClass.getDeclaredMethod("createVirtualDisplay", configClass,
                VirtualDisplay.Callback::class.java, android.os.Handler::class.java, String::class.java)
        } catch (_: NoSuchMethodException) {
            val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            dmgClass.getDeclaredMethod("createVirtualDisplay", configClass, callbackClass, String::class.java)
        }

        createVD.isAccessible = true
        log("Calling DisplayManagerGlobal.createVirtualDisplay...")
        val result = if (createVD.parameterCount == 4) {
            createVD.invoke(dmg, config, null, null, FakeContext.get().packageName)
        } else {
            createVD.invoke(dmg, config, null, FakeContext.get().packageName)
        }
        return result as? VirtualDisplay
    }

    // ── Display IME ──

    private fun setDisplayImePolicy(displayId: Int) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            val windowBinder = getService.invoke(null, "window")
            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            val wm = asInterface.invoke(null, windowBinder)
            val setImePolicy = wm::class.java.getDeclaredMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setImePolicy.isAccessible = true
            setImePolicy.invoke(wm, displayId, 0)
            log("IME policy set to LOCAL for display $displayId")
        } catch (e: Exception) {
            err("setDisplayImePolicy failed (non-fatal): ${e.message}")
        }
    }

    // ── Stack check ──

    private fun checkStackEmpty() {
        Thread({ checkStackEmptyImpl() }, "StackCheck").start()
    }

    private fun checkStackEmptyImpl() {
        try {
            Thread.sleep(300)
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "dumpsys activity activities 2>/dev/null" +
                " | grep -E 'display(Id)?=#?$displayId'" +
                " | grep -c 'Task{'"))
            val buf = ByteArray(64)
            val len = p.inputStream.read(buf)
            p.waitFor()
            val result = if (len > 0) String(buf, 0, len).trim() else "0"
            val taskCount = result.toIntOrNull() ?: 0
            log("Display $displayId has $taskCount task(s)")
            if (taskCount == 0) {
                log("Display $displayId stack is empty")
                enqueueWriteByte(MSG_STACK_EMPTY)
            }
        } catch (e: Exception) {
            err("checkStackEmpty failed: ${e.message}")
        }
    }

    // ── Display power ──

    @Synchronized
    private fun getDisplayControlClass(): Class<*>? {
        if (displayControlLoaded) return displayControlClass
        displayControlLoaded = true

        try {
            displayControlClass = Class.forName("com.android.server.display.DisplayControl")
            return displayControlClass
        } catch (_: ClassNotFoundException) {}

        try {
            val clFactory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createCL = clFactory.getDeclaredMethod("createClassLoader",
                String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            val cl = createCL.invoke(null,
                "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            displayControlClass = cl.loadClass("com.android.server.display.DisplayControl")

            val loadLib = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            loadLib.isAccessible = true
            loadLib.invoke(Runtime.getRuntime(), displayControlClass, "android_servers")

            log("DisplayControl loaded from services.jar")
            return displayControlClass
        } catch (e: Exception) {
            err("Failed to load DisplayControl from services.jar: ${e.message}")
            return null
        }
    }

    private fun setPhysicalDisplayPower(on: Boolean) {
        val mode = if (on) 2 else 0 // POWER_MODE_NORMAL=2, POWER_MODE_OFF=0
        try {
            val scClass = Class.forName("android.view.SurfaceControl")

            var displayIds: LongArray? = null
            val dcClass = getDisplayControlClass()
            if (dcClass != null) {
                try { displayIds = dcClass.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray } catch (_: Exception) {}
            }
            if (displayIds == null) {
                try { displayIds = scClass.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray } catch (_: Exception) {}
            }

            if (displayIds != null && displayIds.isNotEmpty()) {
                val setMode = scClass.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                for (id in displayIds) {
                    var token: android.os.IBinder? = null
                    if (dcClass != null) {
                        try { token = dcClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? android.os.IBinder } catch (_: Exception) {}
                    }
                    if (token == null) {
                        try { token = scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? android.os.IBinder } catch (_: Exception) {}
                    }
                    if (token != null) setMode.invoke(null, token, mode)
                }
                log("Physical display power via SurfaceControl: ${if (on) "ON" else "OFF"}")
                return
            } else {
                err("No physical display IDs found via DisplayControl or SurfaceControl")
            }
        } catch (e: Exception) {
            err("setPhysicalDisplayPower reflection failed: ${e.message}")
        }

        // Fallback: shell command
        if (on) {
            execShell("cmd display power-on 0")
        } else {
            execShell("cmd display power-off 0")
        }
        log("Physical display power via shell fallback: ${if (on) "ON" else "OFF"}")
    }

    // ── Init ──

    private fun initInputManager() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            val inputBinder = getService.invoke(null, "input")
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            inputManager = asInterface.invoke(null, inputBinder)

            injectInputEventMethod = inputManager!!::class.java.getDeclaredMethod(
                "injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType
            )
            injectInputEventMethod!!.isAccessible = true

            try {
                setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (ex: Exception) {
                err("setDisplayId not available: ${ex.message}")
            }

            log("InputManager injection ready (ServiceManager/IInputManager)")
        } catch (e: Exception) {
            err("InputManager init failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun initPersistentShell() {
        try {
            persistentShell = Runtime.getRuntime().exec(arrayOf("sh"))
            shellInput = persistentShell!!.outputStream
        } catch (e: Exception) {
            err("Failed to start persistent shell: ${e.message}")
        }
    }

    // ── Cleanup ──

    private fun cleanup() {
        running = false

        persistentShell?.let {
            try { shellInput?.close() } catch (_: Exception) {}
            it.destroy()
            persistentShell = null
            shellInput = null
        }

        savedScreenOffTimeout?.let {
            try {
                execShell("settings put system screen_off_timeout $it")
                log("Screen timeout restored to ${it}ms")
            } catch (_: Exception) {}
        }

        if (savedLiftWakeup != null || savedProximityWakeup != null) {
            try {
                if (savedLiftWakeup != null) execShell("settings put system lift_wakeup_enabled $savedLiftWakeup")
                if (savedProximityWakeup != null) execShell("settings put system proximity_wakeup_enabled $savedProximityWakeup")
                log("Lift/proximity wake restored")
            } catch (_: Exception) {}
        }

        setPhysicalDisplayPower(true)
        execShell("input keyevent 224") // KEYCODE_WAKEUP
        log("Physical display restored")

        scaler?.stop()
        scaler = null

        virtualDisplay?.release()
        virtualDisplay = null

        encoder?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            encoder = null
        }

        log("Cleanup complete")
    }
}
