package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.os.SystemClock
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.NioReader
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.ProtocolException
import com.dilinkauto.protocol.VideoMsg
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Lightweight server that runs via app_process as shell UID (2000).
 * Creates a real VirtualDisplay, encodes its content as H.264,
 * and streams it directly to the car over TCP (ports 9638/9639).
 *
 * Shell UID can create virtual displays that host any activity.
 * Uses FakeContext (com.android.shell identity) for DisplayManager access,
 * following the same approach as scrcpy.
 *
 * Usage: CLASSPATH=<dex> app_process / com.dilinkauto.vdserver.VirtualDisplayServer <width> <height> <dpi> <phoneHost> <encodeWidth> <encodeHeight> <fps>
 */
class VirtualDisplayServer(
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val phoneHost: String,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val fps: Int
) {
    private val frameIntervalMs = 1000L / fps

    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    // Video write queue — lock-free enqueue from encoder thread, drained by video writer thread
    private val videoWriteQueue = ConcurrentLinkedQueue<FrameCodec.Frame>()
    @Volatile private var videoWriterThread: Thread? = null
    @Volatile private var videoWriteQueueDepth = 0
    private val BACKPRESSURE_THRESHOLD = 6

    // Response write queue — MSG_STACK_EMPTY, MSG_FOCUSED_APP to phone lifecycle channel
    private val responseWriteQueue = ConcurrentLinkedQueue<ByteBuffer>()
    @Volatile private var responseWriterThread: Thread? = null

    private var scaler: SurfaceScaler? = null
    private var scalerThread: Thread? = null
    @Volatile private var running = true

    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null
    private var lastPowerOffTime = 0L

    private var persistentShell: Process? = null
    private var shellInput: OutputStream? = null

    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private var touchDownTime = 0L

    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    // ── Main entry point ──

    companion object {
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11
        private const val MSG_FOCUSED_APP: Byte = 0x12

        private const val CMD_STOP = 0xFF

        private const val BITRATE = 8_000_000
        private const val I_FRAME_INTERVAL = 1
        private const val MAX_POINTERS = 10

        // Ports for direct car communication
        private const val VIDEO_PORT = 9638
        private const val INPUT_PORT = 9639
        // Lifecycle channel port (to phone)
        private const val LIFECYCLE_PORT = 19647

        private var displayControlClass: Class<*>? = null
        private var displayControlLoaded = false

        @JvmStatic
        fun main(args: Array<String>) {
            val w = args.getOrNull(0)?.toInt() ?: 1408
            val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120
            val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w
            val eh = args.getOrNull(5)?.toInt() ?: h
            val fps = args.getOrNull(6)?.toInt() ?: 30

            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$fps")
            VirtualDisplayServer(w, h, d, ph, ew, eh, fps).run()
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

        val success = bindAndServe()
        if (!success) {
            err("Could not establish connections — exiting")
        }

        cleanup()
    }

    // ── Bind and serve car directly ──

    private fun bindAndServe(): Boolean {
        // 1. Bind server sockets for car on 0.0.0.0:9638 (video) and 0.0.0.0:9639 (input)
        val videoServer: ServerSocketChannel
        val inputServer: ServerSocketChannel
        try {
            videoServer = ServerSocketChannel.open()
            videoServer.configureBlocking(false)
            videoServer.socket().reuseAddress = true
            videoServer.socket().bind(InetSocketAddress("0.0.0.0", VIDEO_PORT))
            log("Video server bound on 0.0.0.0:$VIDEO_PORT")

            inputServer = ServerSocketChannel.open()
            inputServer.configureBlocking(false)
            inputServer.socket().reuseAddress = true
            inputServer.socket().bind(InetSocketAddress("0.0.0.0", INPUT_PORT))
            log("Input server bound on 0.0.0.0:$INPUT_PORT")
        } catch (e: Exception) {
            err("Failed to bind server sockets: ${e.message}")
            return false
        }

        // 2. Connect to phone for lifecycle channel
        val phoneChannel = connectToPhoneHost()
        if (phoneChannel == null) {
            try { videoServer.close() } catch (_: Exception) {}
            try { inputServer.close() } catch (_: Exception) {}
            return false
        }

        // 3. Send MSG_DISPLAY_READY — phone now sends VD_PORTS_BOUND to car
        try {
            sendDisplayReady(phoneChannel)
            log("Display ready sent to phone")
        } catch (e: Exception) {
            err("Failed to send display ready: ${e.message}")
            try { phoneChannel.close() } catch (_: Exception) {}
            try { videoServer.close() } catch (_: Exception) {}
            try { inputServer.close() } catch (_: Exception) {}
            return false
        }

        // 4. Start lifecycle reader (CMD_STOP from phone)
        val lifecycleThread = Thread({
            try { readLifecycleCommands(phoneChannel) } catch (e: Exception) {
                err("Lifecycle reader error: ${e.message}")
            }
        }, "LifecycleReader").apply { isDaemon = true }
        lifecycleThread.start()

        // 5. Start response writer (MSG_STACK_EMPTY, MSG_FOCUSED_APP to phone)
        val respThread = Thread({
            try { runResponseWriter(phoneChannel) } catch (e: Exception) {
                err("Response writer error: ${e.message}")
            }
        }, "ResponseWriter").apply { isDaemon = true }
        respThread.start()

        // 6. Launch home activity and power off display
        execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME")
        log("Home launched on display $displayId")
        setPhysicalDisplayPower(false)
        lastPowerOffTime = System.currentTimeMillis()

        // 7. Accept car connections (with timeout)
        val carVideo: SocketChannel
        val carInput: SocketChannel
        try {
            carVideo = acceptCarChannel(videoServer, "video", 30000)
                ?: return false.also { err("Car did not connect on video port") }
            carInput = acceptCarChannel(inputServer, "input", 30000)
                ?: return false.also { err("Car did not connect on input port") }
        } finally {
            try { videoServer.close() } catch (_: Exception) {}
            try { inputServer.close() } catch (_: Exception) {}
        }

        log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")

        // 8. Start threads for the session
        startVideoWriter(carVideo)
        startTouchAndCommandReader(carInput)
        startEncoderOutput()

        log("All threads started — streaming directly to car")
        return true
    }

    /** Connect to phone app on phoneHost:LIFECYCLE_PORT for lifecycle commands */
    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, LIFECYCLE_PORT)
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                log("Connecting to phone lifecycle channel on $phoneHost:$LIFECYCLE_PORT (attempt ${attempt + 1})...")
                ch = SocketChannel.open()
                ch.configureBlocking(false)
                ch.connect(addr)

                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) {
                    if (!running || System.currentTimeMillis() > deadline) {
                        ch.close()
                        throw ConnectException("timeout or stopped")
                    }
                    Thread.sleep(50)
                }

                ch.configureBlocking(false)
                ch.socket().tcpNoDelay = true
                log("Connected to phone lifecycle channel")
                return ch
            } catch (e: ConnectException) {
                ch?.close()
                if (attempt < 59) Thread.sleep(200)
            } catch (e: Exception) {
                ch?.close()
                err("Lifecycle connection error: ${e.message}")
                break
            }
        }
        return null
    }

    /** Send MSG_DISPLAY_READY to phone (blocking write before NIO threads start) */
    private fun sendDisplayReady(ch: SocketChannel) {
        val hasInjection = checkDirectInjectionWorks()
        val flags: Byte = if (hasInjection) 1 else 0
        val readyBuf = ByteBuffer.allocate(6)
        readyBuf.put(MSG_DISPLAY_READY)
        readyBuf.putInt(displayId)
        readyBuf.put(flags)
        readyBuf.flip()
        ch.configureBlocking(true)
        while (readyBuf.hasRemaining()) ch.write(readyBuf)
        ch.configureBlocking(false)
        log("Display ready sent: id=$displayId ${width}x${height}@${dpi} injectInput=$hasInjection")
    }

    /** Accept a single connection on the server socket with timeout */
    private fun acceptCarChannel(server: ServerSocketChannel, name: String, timeoutMs: Int): SocketChannel? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val ch = server.accept()
            if (ch != null) {
                ch.configureBlocking(false)
                ch.socket().tcpNoDelay = true
                ch.socket().sendBufferSize = 262144
                ch.socket().receiveBufferSize = 262144
                log("Accepted $name connection from ${ch.remoteAddress}")
                return ch
            }
            Thread.sleep(50)
        }
        return null
    }

    // ── Video path (port 9638 → Car) ──

    /** Start the video writer thread that drains videoWriteQueue and sends to car */
    private fun startVideoWriter(carChannel: SocketChannel) {
        val wt = Thread({
            log("VideoWriter thread started")
            try {
                var writeCount = 0L
                while (running) {
                    videoWriterThread = Thread.currentThread()
                    val frame = videoWriteQueue.poll()
                    if (frame == null) {
                        LockSupport.park()
                        continue
                    }
                    videoWriteQueueDepth--
                    FrameCodec.writeFrameToChannel(carChannel, frame)
                    writeCount++
                    if (writeCount % 60 == 0L) {
                        log("VideoWriter: $writeCount frames, queueDepth=$videoWriteQueueDepth")
                    }
                }
            } catch (e: Exception) {
                err("VideoWriter thread error: ${e.javaClass.simpleName}: ${e.message}")
            }
            // Don't set running=false — only lifecycle errors or encoder errors trigger shutdown
            log("VideoWriter thread exited")
        }, "VideoWriter").apply { isDaemon = true }
        wt.start()
    }

    // ── Encoder output ──

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setLong("repeat-previous-frame-after", 500_000L)

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${BITRATE / 1_000_000}Mbps Baseline low-latency")
        } catch (e: Exception) {
            throw IOException("Failed to create encoder: ${e.message}", e)
        }
    }

    private fun startEncoderOutput() {
        Thread({
            log("VideoOutput thread started")
            try { readEncoderOutput() } catch (e: Exception) {
                err("VideoOutput thread CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
            log("VideoOutput thread exited (running=$running)")
        }, "VideoOutput").apply { isDaemon = true }.start()
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
        var skippedFrameCount = 0L

        while (running) {
            try {
                val outputIndex = enc.dequeueOutputBuffer(info, frameIntervalMs * 1000)
                if (outputIndex >= 0) {
                    val now = System.currentTimeMillis()
                    val gap = now - lastFrameTime

                    val buffer = enc.getOutputBuffer(outputIndex)
                    if (buffer == null || info.size <= 0) {
                        noOutputCount++
                        enc.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    lastFrameTime = now
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val size = info.size

                    if (isKeyFrame) {
                        keyFrameCount++
                        val sinceLast = if (lastKeyFrameAt > 0) now - lastKeyFrameAt else 0
                        log("KEYFRAME #$keyFrameCount at frame $frameCount size=$size sinceLast=${sinceLast}ms")
                        lastKeyFrameAt = now
                    }

                    // Encoder backpressure: drop P-frames when car isn't consuming fast enough
                    if (!isConfig && !isKeyFrame && videoWriteQueueDepth > BACKPRESSURE_THRESHOLD) {
                        enc.releaseOutputBuffer(outputIndex, false)
                        skippedFrameCount++
                        if (skippedFrameCount <= 3 || skippedFrameCount % 60 == 0L) {
                            log("Encoder backpressure: skip P-frame (queueDepth=$videoWriteQueueDepth)")
                        }
                        continue
                    }

                    // Build FrameCodec frame with proper protocol framing
                    val payload = ByteArray(size)
                    buffer.get(payload, 0, size)
                    val frame = FrameCodec.Frame(
                        channel = Channel.VIDEO,
                        messageType = if (isConfig) VideoMsg.CONFIG else VideoMsg.FRAME,
                        payload = payload
                    )
                    videoWriteQueue.add(frame)
                    videoWriteQueueDepth++
                    val wt = videoWriterThread
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
                }
            } catch (e: Exception) {
                err("Video output error: ${e.message}")
                running = false
                break
            }
        }
    }

    // ── Touch and command reader (port 9639 ← Car) ──

    private fun startTouchAndCommandReader(carChannel: SocketChannel) {
        Thread({
            log("TouchReader thread started")
            try {
                readTouchAndCommands(carChannel)
            } catch (e: Exception) {
                err("TouchReader thread error: ${e.javaClass.simpleName}: ${e.message}")
            }
            // Don't set running=false — only lifecycle reader or writer errors should trigger shutdown
            log("TouchReader thread exited")
        }, "TouchReader").apply { isDaemon = true }.start()
    }

    private fun readTouchAndCommands(ch: SocketChannel) {
        val reader = NioReader(ch, 65536, frameIntervalMs)
        var cmdCount = 0L
        log("Touch/Command reader started (NioReader)")

        while (running) {
            val frame = try {
                FrameCodec.readFrameBlocking(reader)
            } catch (e: Exception) {
                err("TouchReader read error: ${e.message}")
                null
            }
            if (frame == null) {
                log("Touch/Command reader: connection closed")
                break
            }

            when (frame.channel) {
                Channel.INPUT -> handleTouchFrame(frame)
                Channel.CONTROL -> {
                    handleCarCommand(frame)
                    cmdCount++
                }
                else -> err("Unknown channel on input port: ${frame.channel}")
            }
        }
        reader.close()
        // Don't set running=false — a single connection drop shouldn't kill the whole server.
        // The video writer or lifecycle reader will detect real failures.
    }

    private fun handleTouchFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            InputMsg.TOUCH_MOVE_BATCH -> {
                val batch = TouchMoveBatch.decode(frame.payload)
                for (p in batch.pointers) {
                    val pixelX = (p.x * width).toInt()
                    val pixelY = (p.y * height).toInt()
                    injectTouch(1, p.pointerId, pixelX, pixelY, p.pressure)
                }
            }
            InputMsg.TOUCH_DOWN, InputMsg.TOUCH_MOVE, InputMsg.TOUCH_UP -> {
                val event = TouchEvent.decode(frame.payload)
                val pixelX = (event.x * width).toInt()
                val pixelY = (event.y * height).toInt()
                val action = when (frame.messageType) {
                    InputMsg.TOUCH_DOWN -> 0
                    InputMsg.TOUCH_MOVE -> 1
                    InputMsg.TOUCH_UP -> 2
                    else -> return
                }
                injectTouch(action, event.pointerId, pixelX, pixelY, event.pressure)
            }
        }
    }

    /** Handle commands from car received on port 9639 Channel.CONTROL */
    private fun handleCarCommand(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.LAUNCH_APP -> {
                val msg = LaunchAppMessage.decode(frame.payload)
                launchApp(msg.packageName)
            }
            ControlMsg.GO_BACK -> {
                execFast("input -d $displayId keyevent 4")
                checkStackEmpty()
            }
            ControlMsg.GO_HOME -> {
                log("Home: no-op (car handles launcher navigation)")
            }
            ControlMsg.APP_UNINSTALL -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                log("Uninstalling: $pkg")
                execShell("pm uninstall $pkg")
            }
            ControlMsg.APP_INFO -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                log("Opening app info on VD for: $pkg")
                val settingsComponent = execShellOutput(
                    "cmd package resolve-activity --brief -a android.settings.APPLICATION_DETAILS_SETTINGS com.android.settings"
                )?.trim()
                if (!settingsComponent.isNullOrEmpty()) {
                    execShell("am start --display $displayId -n $settingsComponent -d \"package:$pkg\"")
                } else {
                    execShell("am start --display $displayId -a android.settings.APPLICATION_DETAILS_SETTINGS -d \"package:$pkg\"")
                }
            }
            ControlMsg.APP_SHORTCUTS -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                log("Querying shortcuts for: $pkg")
                val output = execShellFullOutput("cmd shortcut get-shortcuts --package $pkg 2>&1") ?: ""
                handleShortcutsResult(pkg, output)
            }
            ControlMsg.APP_SHORTCUT_ACTION -> {
                val action = com.dilinkauto.protocol.AppShortcutActionMessage.decode(frame.payload)
                log("Executing shortcut: ${action.shortcutId} for ${action.packageName}")
                var result = execShellFullOutput(
                    "cmd shortcut execute -s ${action.packageName} ${action.shortcutId} $displayId 2>&1"
                ) ?: ""
                if (result.contains("Error") || result.contains("Unknown cmd")) {
                    log("cmd shortcut failed, trying am start")
                    result = execShellFullOutput(
                        "am start --display $displayId " +
                        "-a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER " +
                        "-f 0x10200000 " +
                        "--es shortcut_id \"${action.shortcutId}\" " +
                        "${action.packageName} 2>&1"
                    ) ?: ""
                }
            }
            else -> err("Unknown car command: 0x${frame.messageType.toString(16)}")
        }
    }

    // ── Lifecycle channel (19637 ← Phone) ──

    private fun readLifecycleCommands(ch: SocketChannel) {
        val reader = NioReader(ch, 4096, frameIntervalMs)
        log("Lifecycle reader started")
        try {
            while (running) {
                val cmd = reader.readByteBlocking().toInt() and 0xFF
                when (cmd) {
                    CMD_STOP -> {
                        log("Received CMD_STOP from phone")
                        running = false
                    }
                    else -> err("Unknown lifecycle command: 0x${cmd.toString(16)}")
                }
            }
        } catch (e: IOException) {
            if (running) err("Lifecycle reader error: ${e.message}")
        } finally {
            running = false
            reader.close()
        }
    }

    // ── Response writer (19637 → Phone) ──

    private fun runResponseWriter(ch: SocketChannel) {
        log("ResponseWriter thread started")
        try {
            while (running) {
                responseWriterThread = Thread.currentThread()
                val buf = responseWriteQueue.poll()
                if (buf == null) {
                    LockSupport.park()
                    continue
                }
                FrameCodec.writeAll(ch, buf)
            }
        } catch (e: Exception) {
            err("ResponseWriter error: ${e.message}")
        }
        log("ResponseWriter thread exited")
    }

    private fun enqueueResponseByte(msgType: Byte) {
        val buf = ByteBuffer.allocate(1)
        buf.put(msgType)
        buf.flip()
        responseWriteQueue.add(buf)
        val wt = responseWriterThread
        if (wt != null) LockSupport.unpark(wt)
    }

    private fun enqueueResponse(msgType: Byte, payload: ByteArray) {
        val buf = ByteBuffer.allocate(1 + 4 + payload.size)
        buf.put(msgType)
        buf.putInt(payload.size)
        buf.put(payload)
        buf.flip()
        responseWriteQueue.add(buf)
        val wt = responseWriterThread
        if (wt != null) LockSupport.unpark(wt)
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
        when (action) {
            0 -> {
                activePointers[pointerId] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
                if (activePointers.size == 1) touchDownTime = android.os.SystemClock.uptimeMillis()
            }
            1 -> {
                val coords = activePointers[pointerId] ?: return
                coords[0] = x.toFloat(); coords[1] = y.toFloat(); coords[2] = pressure
            }
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

    private fun execShellFullOutput(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun execShell(command: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            p.waitFor()
        } catch (e: Exception) {
            err("execShell error: ${e.message}")
        }
    }

    // ── Virtual Display creation (unchanged) ──

    private fun createVirtualDisplay() {
        val enc = encoder ?: return
        val encoderSurface = enc.createInputSurface()
        enc.start()

        val vdSurface = if (width != encodeWidth || height != encodeHeight) {
            log("GPU scaling: VD ${width}x${height} → encoder ${encodeWidth}x${encodeHeight}")
            scaler = SurfaceScaler(encoderSurface, width, height, encodeWidth, encodeHeight, frameIntervalMs)
            scaler!!.start()
            scaler!!.getInputSurface()
        } else encoderSurface

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
        }

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
                    or (1 shl 6)
                    or (1 shl 10)
                    or (1 shl 11)
                    or (1 shl 13)
                    or (1 shl 14))

            log("Creating VD with flags=0x${flags.toString(16)}")
            virtualDisplay = dm.createVirtualDisplay("DiLinkAutoVD", width, height, dpi, vdSurface, flags)

            if (virtualDisplay != null) {
                displayId = virtualDisplay!!.display.displayId
                log("VirtualDisplay created via DisplayManager: displayId=$displayId")
                setDisplayImePolicy(displayId)
                execShell("settings put global force_resizable_activities 1")
                log("Enabled force_resizable_activities for VD")

                savedScreenOffTimeout = execShellOutput("settings get system screen_off_timeout") ?: "60000"
                execShell("settings put system screen_off_timeout 2147483647")
                log("Screen timeout disabled (was ${savedScreenOffTimeout}ms)")

                savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")
                savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")
                execShell("settings put system lift_wakeup_enabled 0")
                execShell("settings put system proximity_wakeup_enabled 0")
                log("Lift/proximity wake disabled")
            } else {
                err("DisplayManager.createVirtualDisplay returned null")
            }
        } catch (e: Exception) {
            err("DisplayManager approach failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun createVirtualDisplayViaGlobal(surface: Surface): VirtualDisplay? {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val getInstance = dmgClass.getDeclaredMethod("getInstance")
        val dmg = getInstance.invoke(null)
        log("Got DisplayManagerGlobal instance")

        val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or (1 shl 6) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 14))

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

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val createVD: Method = try {
            dmgClass.getDeclaredMethod("createVirtualDisplay", configClass,
                VirtualDisplay.Callback::class.java, android.os.Handler::class.java, String::class.java)
        } catch (_: NoSuchMethodException) {
            val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            dmgClass.getDeclaredMethod("createVirtualDisplay", configClass, callbackClass, String::class.java)
        }

        createVD.isAccessible = true
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
                " | grep -B 20 'Task{'" +
                " | grep -E 'display(Id)?=#?$displayId'" +
                " | wc -l"))
            val buf = ByteArray(64)
            val len = p.inputStream.read(buf)
            p.waitFor()
            val result = if (len > 0) String(buf, 0, len).trim() else "0"
            val taskCount = result.toIntOrNull() ?: 0
            log("Display $displayId has $taskCount task(s)")
            if (taskCount == 0) {
                log("Display $displayId stack is empty")
                enqueueResponseByte(MSG_STACK_EMPTY)
            } else {
                val focusedPkg = getFocusedPackageOnDisplay()
                if (focusedPkg != null) {
                    log("Focused app on display $displayId: $focusedPkg")
                    val pkgBytes = focusedPkg.toByteArray(Charsets.UTF_8)
                    enqueueResponse(MSG_FOCUSED_APP, pkgBytes)
                } else {
                    log("Display $displayId has tasks but no focused app — treating as empty")
                    enqueueResponseByte(MSG_STACK_EMPTY)
                }
            }
        } catch (e: Exception) {
            err("checkStackEmpty failed: ${e.message}")
        }
    }

    private fun getFocusedPackageOnDisplay(): String? {
        return try {
            val fp = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "dumpsys activity activities 2>/dev/null" +
                " | grep -B 5 'mResumedActivity'" +
                " | grep -oP '[a-zA-Z][a-zA-Z0-9_.]+(?=/)'" +
                " | head -1"))
            val fbuf = ByteArray(256)
            val flen = fp.inputStream.read(fbuf)
            fp.waitFor()
            if (flen > 0) {
                val pkg = String(fbuf, 0, flen).trim()
                if (pkg.isNotEmpty() && !pkg.startsWith("com.android.") && pkg != "android") {
                    pkg
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Shortcuts ──

    private fun handleShortcutsResult(pkg: String, output: String) {
        log("Shortcut result for $pkg: ${output.length} chars")
        val pkgBytes = pkg.toByteArray(Charsets.UTF_8)
        val dataBytes = output.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 4 + pkgBytes.size + 4 + dataBytes.size)
        buf.put(0x13.toByte()) // MSG_SHORTCUTS_RESULT
        buf.putInt(pkgBytes.size)
        buf.put(pkgBytes)
        buf.putInt(dataBytes.size)
        buf.put(dataBytes)
        buf.flip()
        responseWriteQueue.add(buf)
        val wt = responseWriterThread
        if (wt != null) LockSupport.unpark(wt)
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
        val mode = if (on) 2 else 0
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
            }
        } catch (e: Exception) {
            err("setPhysicalDisplayPower reflection failed: ${e.message}")
        }

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

            log("InputManager injection ready")
        } catch (e: Exception) {
            err("InputManager init failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun checkDirectInjectionWorks(): Boolean {
        if (inputManager == null || injectInputEventMethod == null) return false
        try {
            val displayId = this.displayId
            if (displayId < 0) return false
            val downTime = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 0f; y = 0f; pressure = 1.0f })
            val event = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { setDisplayIdMethod?.invoke(event, displayId) } catch (_: Exception) {}
            injectInputEventMethod!!.invoke(inputManager, event, 0)
            val upEvent = MotionEvent.obtain(downTime, downTime + 1,
                MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { setDisplayIdMethod?.invoke(upEvent, displayId) } catch (_: Exception) {}
            injectInputEventMethod!!.invoke(inputManager, upEvent, 0)
            event.recycle(); upEvent.recycle()
            log("Direct injection verified")
            return true
        } catch (e: Exception) {
            log("Direct injection not available: ${e.javaClass.simpleName}")
            return false
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
        execShell("input keyevent 224")
        log("Physical display restored")

        scaler?.stop()
        scaler = null

        virtualDisplay?.release()
        virtualDisplay = null

        encoder?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            encoder = null
        }

        // Unpark any parked threads so they can check !running and exit
        val vwt = videoWriterThread; if (vwt != null) LockSupport.unpark(vwt)
        val rwt = responseWriterThread; if (rwt != null) LockSupport.unpark(rwt)

        log("Cleanup complete")
    }
}
