package com.dilinkauto.client.display

import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.VideoConfig
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.NioReader
import com.dilinkauto.protocol.VideoMsg
import kotlinx.coroutines.*
import android.os.PowerManager
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Connects to the VirtualDisplayServer helper process running as shell UID.
 * Relays H.264 video from the helper to the car via the existing Connection.
 * Sends commands (launch app, input, back, home) to the helper.
 *
 * Accept uses NIO ServerSocketChannel (cancellation-aware, no EADDRINUSE).
 * Reads use NioReader with Selector for instant wakeup on data.
 * Writes use FrameCodec.writeAll with timeout protection.
 */
class VirtualDisplayClient(
    private val videoConnection: Connection,
    private val controlConnection: Connection,
    private val scope: CoroutineScope,
    private val appContext: android.content.Context
) {
    @Volatile private var channel: SocketChannel? = null
    @Volatile private var reader: NioReader? = null
    private val writeBuf = ByteBuffer.allocate(64) // fixed-size commands (touch)
    private val writeLock = Any()
    private var videoJob: Job? = null

    @Volatile
    var displayId: Int = -1
    var hasDirectInjection: Boolean = false
        private set

    @Volatile
    var isConnected = false
        private set

    private var serverChannel: ServerSocketChannel? = null

    // Track launched apps in order — used to know which app gains focus after back presses
    private val launchedAppStack = mutableListOf<String>()

    /**
     * Opens the ServerSocket immediately (synchronous, instant).
     * Call this BEFORE sending the handshake response so the socket is ready
     * when the car deploys the VD server.
     */
    fun startListening(port: Int = SERVER_PORT) {
        try { serverChannel?.close() } catch (_: Exception) {}
        val ch = ServerSocketChannel.open()
        ch.configureBlocking(false)
        ch.socket().reuseAddress = true
        ch.socket().bind(InetSocketAddress("127.0.0.1", port))
        serverChannel = ch
        FileLog.i(TAG, "Listening for VD server on localhost:$port")
    }

    /**
     * Waits for the VD server to connect on the already-open ServerSocket.
     * Call startListening() first.
     */
    suspend fun acceptConnection(port: Int = SERVER_PORT, timeoutMs: Int = 60000): Boolean {
        return withContext(Dispatchers.IO) {
            val ch = serverChannel
            if (ch == null || !ch.isOpen) {
                FileLog.e(TAG, "acceptConnection: ServerSocket not open, call startListening() first")
                return@withContext false
            }
            try {
                FileLog.i(TAG, "Waiting for VD server connection...")

                // Poll for connection with cancellation support
                val deadline = System.currentTimeMillis() + timeoutMs
                var accepted: SocketChannel? = null
                while (isActive && System.currentTimeMillis() < deadline) {
                    accepted = ch.accept()
                    if (accepted != null) break
                    delay(VideoConfig.FRAME_INTERVAL_MS) // Check every frame interval
                }

                if (accepted == null) {
                    FileLog.w(TAG, "VD server did not connect within ${timeoutMs}ms")
                    return@withContext false
                }

                accepted.configureBlocking(false)
                accepted.socket().tcpNoDelay = true
                channel = accepted
                val rdr = NioReader(accepted, NioReader.DEFAULT_CAPACITY) // 256KB to avoid realloc on keyframes
                reader = rdr

                val msgType = rdr.readByte()
                if (msgType == MSG_DISPLAY_READY) {
                    displayId = rdr.readInt()
                    val flags = rdr.readByte()
                    hasDirectInjection = (flags.toInt() and 1) != 0
                    isConnected = true
                    FileLog.i(TAG, "VD server connected, displayId=$displayId directInjection=$hasDirectInjection")
                    startVideoRelay()
                    true
                } else {
                    FileLog.w(TAG, "Unexpected first message from VD server: $msgType")
                    accepted.close()
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLog.e(TAG, "VD accept failed: ${e.message}")
                false
            } finally {
                // Close server socket after accept (or on failure) — no longer needed
                try { serverChannel?.close() } catch (_: Exception) {}
                serverChannel = null
            }
        }
    }

    /**
     * Reads H.264 frames from the VD server and forwards them to the car.
     */
    private fun startVideoRelay() {
        videoJob = scope.launch(Dispatchers.IO) {
            val rdr = reader ?: return@launch
            var frameCount = 0L
            var lastFrameTime = System.currentTimeMillis()

            FileLog.i(TAG, "Video relay started")
            try {
                while (isActive && isConnected) {
                    if (frameCount > 0 && frameCount % 30 == 0L) {
                        FileLog.d(TAG, "relay: waiting for next frame (count=$frameCount)")
                    }

                    val msgType = rdr.readByte()
                    val now = System.currentTimeMillis()
                    val gap = now - lastFrameTime
                    lastFrameTime = now

                    if (frameCount <= 5 || frameCount % 30 == 0L || gap > 1000) {
                        FileLog.d(TAG, "relay: got msgType=0x${msgType.toString(16)} gap=${gap}ms count=$frameCount")
                    }

                    // Stack empty signal — no payload, relay to car
                    if (msgType == MSG_STACK_EMPTY) {
                        FileLog.i(TAG, "VD stack empty — notifying car, appStack=$launchedAppStack")
                        // If we still have apps in our stack, the VD might consider HOME
                        // as a task. Pop from stack and notify car about the previous app.
                        if (launchedAppStack.size > 1) {
                            launchedAppStack.removeAt(launchedAppStack.lastIndex)
                            val nowFocused = launchedAppStack.last()
                            FileLog.i(TAG, "App stack pop → now focused: $nowFocused")
                            if (controlConnection.isConnected) {
                                val pkgBytes = nowFocused.toByteArray(Charsets.UTF_8)
                                controlConnection.sendControl(
                                    com.dilinkauto.protocol.ControlMsg.FOCUSED_APP, pkgBytes)
                            }
                        } else {
                            launchedAppStack.clear()
                            if (controlConnection.isConnected) {
                                controlConnection.sendControl(com.dilinkauto.protocol.ControlMsg.VD_STACK_EMPTY)
                            }
                        }
                        continue
                    }

                    // Focused app signal — package name follows
                    if (msgType == MSG_FOCUSED_APP) {
                        val pkgLen = rdr.readInt()
                        val pkgBytes = ByteArray(pkgLen)
                        rdr.readFully(pkgBytes, 0, pkgLen)
                        val focusedPkg = String(pkgBytes, Charsets.UTF_8)
                        FileLog.i(TAG, "VD focused app: $focusedPkg (appStack=$launchedAppStack)")
                        // Sync our app stack with the VD's reality
                        if (launchedAppStack.isNotEmpty() && launchedAppStack.last() != focusedPkg) {
                            // The VD reports a different focused app — our stack is stale
                            if (focusedPkg in launchedAppStack) {
                                while (launchedAppStack.isNotEmpty() && launchedAppStack.last() != focusedPkg) {
                                    launchedAppStack.removeAt(launchedAppStack.lastIndex)
                                }
                            } else {
                                // New focused app not in our stack — add it
                                launchedAppStack.add(focusedPkg)
                            }
                        }
                        if (controlConnection.isConnected) {
                            controlConnection.sendControl(
                                com.dilinkauto.protocol.ControlMsg.FOCUSED_APP, pkgBytes)
                        }
                        continue
                    }

                    val size = rdr.readInt()

                    if (size > 100000 || (frameCount > 0 && frameCount % 30 == 0L)) {
                        FileLog.d(TAG, "relay: reading payload size=$size")
                    }

                    val data = ByteArray(size)
                    rdr.readFully(data, 0, size)

                    val videoMsgType = when (msgType) {
                        MSG_VIDEO_CONFIG -> VideoMsg.CONFIG
                        MSG_VIDEO_FRAME -> VideoMsg.FRAME
                        else -> {
                            FileLog.w(TAG, "relay: unknown msgType=0x${msgType.toString(16)} size=$size — skipping")
                            continue
                        }
                    }

                    if (videoConnection.isConnected) {
                        videoConnection.sendVideo(videoMsgType, data)
                        frameCount++
                        if (frameCount % 60 == 0L) {
                            FileLog.d(TAG, "Relayed $frameCount frames to car")
                        }
                    } else {
                        FileLog.w(TAG, "relay: video connection not connected, dropping frame")
                    }
                }
            } catch (e: Exception) {
                FileLog.e(TAG, "Video relay error", e)
                isConnected = false
            }
        }
    }

    // ─── Commands ───

    fun launchApp(packageName: String) {
        // Track launched app in stack
        if (launchedAppStack.isEmpty() || launchedAppStack.last() != packageName) {
            launchedAppStack.add(packageName)
            FileLog.i(TAG, "App stack: $launchedAppStack")
        }
        sendCommand { buf ->
            val bytes = packageName.toByteArray(Charsets.UTF_8)
            buf.put(CMD_LAUNCH_APP.toByte())
            buf.putInt(bytes.size)
            buf.put(bytes)
        }
    }

    fun goBack() {
        sendCommand { it.put(CMD_GO_BACK.toByte()) }
    }

    fun goHome() {
        sendCommand { it.put(CMD_GO_HOME.toByte()) }
    }

    fun tap(x: Int, y: Int) {
        sendCommand { buf ->
            buf.put(CMD_INPUT_TAP.toByte())
            buf.putInt(x)
            buf.putInt(y)
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        sendCommand { buf ->
            buf.put(CMD_INPUT_SWIPE.toByte())
            buf.putInt(x1)
            buf.putInt(y1)
            buf.putInt(x2)
            buf.putInt(y2)
            buf.putInt(durationMs)
        }
    }

    fun uninstallApp(packageName: String) {
        sendCommand { buf ->
            val bytes = packageName.toByteArray(Charsets.UTF_8)
            buf.put(CMD_UNINSTALL.toByte())
            buf.putInt(bytes.size)
            buf.put(bytes)
        }
    }

    fun openAppInfo(packageName: String) {
        sendCommand { buf ->
            val bytes = packageName.toByteArray(Charsets.UTF_8)
            buf.put(CMD_OPEN_APP_INFO.toByte())
            buf.putInt(bytes.size)
            buf.put(bytes)
        }
    }

    /**
     * Send a raw touch event for immediate MotionEvent injection on the VD.
     * Synchronous write — avoids coroutine dispatch latency for touch events.
     */
    private var touchWriteCount = 0L

    fun touch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float) {
        val ch = channel
        if (ch == null) {
            if (touchWriteCount == 0L) FileLog.w(TAG, "touch: channel is null, dropping")
            return
        }
        try {
            synchronized(writeLock) {
                writeBuf.clear()
                writeBuf.put(CMD_INPUT_TOUCH.toByte())
                writeBuf.put(action.toByte())
                writeBuf.putInt(pointerId)
                writeBuf.putInt(x)
                writeBuf.putInt(y)
                writeBuf.putFloat(pressure)
                writeBuf.flip()
                FrameCodec.writeAll(ch, writeBuf)
                touchWriteCount++
                if (touchWriteCount <= 3 || touchWriteCount % 100 == 0L) {
                    FileLog.i(TAG, "touch #$touchWriteCount action=$action ptr=$pointerId x=$x y=$y → ch=${ch.isOpen}")
                }
            }
        } catch (e: Exception) {
            FileLog.e(TAG, "Failed to send touch #$touchWriteCount ${e.javaClass.simpleName}: ${e.message} ch.isOpen=${ch.isOpen} ch.isConnected=${ch.isConnected}")
        }
    }

    /** Async command send via coroutine — for non-latency-critical commands */
    private fun sendCommand(writer: (ByteBuffer) -> Unit) {
        val ch = channel ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val buf = ByteBuffer.allocate(1024)
                writer(buf)
                buf.flip()
                synchronized(writeLock) {
                    FrameCodec.writeAll(ch, buf)
                }
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send command", e)
            }
        }
    }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    fun disconnect() {
        isConnected = false
        videoJob?.cancel()
        reader?.close() // wake selector so video relay exits promptly
        try { serverChannel?.close() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        launchedAppStack.clear()

        // Restore physical display — VD server cleanup may not run if process was killed.
        // The VD server uses SurfaceControl / cmd display power-off (hardware-level off).
        // Regular WakeLocks can't recover from this state — we need multiple fallbacks:
        // 1. PowerManager.wakeUp() via reflection (system-level wake)
        // 2. SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP (framework-level)
        // 3. Start MainActivity with FLAG_TURN_SCREEN_ON (WindowManager-level)
        // 4. Shell command fallback (cmd display power-on + keyevent)
        scope.launch(Dispatchers.IO) {
            try {
                FileLog.i(TAG, "Restoring physical display after VD disconnect")
                val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager

                // Layer 1: PowerManager.wakeUp() — system-level wake, most direct
                try {
                    val wakeUp = PowerManager::class.java.getDeclaredMethod(
                        "wakeUp", Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, String::class.java
                    )
                    wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis(),
                        5 /* WAKE_REASON_APPLICATION */, "DiLink:restore")
                    FileLog.i(TAG, "Display wakeUp() succeeded")
                } catch (e: Exception) {
                    FileLog.d(TAG, "wakeUp() not available: ${e.message}")
                }

                // Layer 2: Strong WakeLock with ACQUIRE_CAUSES_WAKEUP
                @Suppress("DEPRECATION")
                val flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE
                val wl = pm.newWakeLock(flags, "DiLink:display:restore")
                wl.acquire(3000)
                wl.release()

                // Layer 3: Launch activity with FLAG_TURN_SCREEN_ON — WindowManager
                // wakes the display when bringing this activity to the foreground
                try {
                    val intent = android.content.Intent(appContext, Class.forName("com.dilinkauto.client.MainActivity"))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.addFlags(0x10000000) // FLAG_TURN_SCREEN_ON
                    appContext.startActivity(intent)
                    FileLog.i(TAG, "Launched MainActivity with FLAG_TURN_SCREEN_ON")
                } catch (e: Exception) {
                    FileLog.d(TAG, "Activity launch for wake failed: ${e.message}")
                }

                // Layer 4: Shell fallback (may work on some devices)
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd display power-on 2>/dev/null; input keyevent 224 2>/dev/null")).waitFor()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                FileLog.w(TAG, "Display restore failed: ${e.message}")
            }
        }
        serverChannel = null
        channel = null
        reader = null
        displayId = -1
        FileLog.i(TAG, "Disconnected from VD server")
    }

    companion object {
        private const val TAG = "VirtualDisplayClient"
        const val SERVER_PORT = 19637

        // Must match VirtualDisplayServer constants
        private const val MSG_VIDEO_CONFIG: Byte = 0x01
        private const val MSG_VIDEO_FRAME: Byte = 0x02
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11
        private const val MSG_FOCUSED_APP: Byte = 0x12
        private const val CMD_LAUNCH_APP = 0x20
        private const val CMD_GO_BACK = 0x21
        private const val CMD_GO_HOME = 0x22
        private const val CMD_INPUT_TAP = 0x30
        private const val CMD_INPUT_SWIPE = 0x31
        private const val CMD_INPUT_TOUCH = 0x32
        private const val CMD_UNINSTALL = 0x23
        private const val CMD_OPEN_APP_INFO = 0x24
    }
}
