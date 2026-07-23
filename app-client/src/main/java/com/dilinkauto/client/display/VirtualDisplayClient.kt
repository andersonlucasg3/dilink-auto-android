package com.dilinkauto.client.display

import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.NioReader
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Lifecycle channel to the VD server process running as shell UID.
 *
 * Receives display-ready signal, stack-empty and focused-app notifications,
 * and shortcut query results from the VD server over localhost.
 * Sends CMD_STOP and shortcut queries to the VD server.
 *
 * Video and touch flow directly between VD server and car (ports 9638/9639).
 * This class handles only the lifecycle/command channel on localhost:19647.
 */
class VirtualDisplayClient(
    private val scope: CoroutineScope,
    private val appContext: android.content.Context
) {
    @Volatile private var channel: SocketChannel? = null
    @Volatile private var reader: NioReader? = null
    private val writeBuf = ByteBuffer.allocate(300) // enough for launch app with package name + header
    private val writeLock = Any()

    @Volatile
    var displayId: Int = -1
    var hasDirectInjection: Boolean = false
        private set

    @Volatile
    var isConnected = false
        private set

    @Volatile private var serverChannel: ServerSocketChannel? = null
    private var commandRelayJob: Job? = null

    // Callbacks for relaying VD signals to ConnectionService
    var onStackEmpty: (() -> Unit)? = null
    var onFocusedApp: ((String) -> Unit)? = null
    var onDisplayReady: (() -> Unit)? = null

    // Async response channels for VD server shortcut queries
    private val shortcutResponses = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * Opens the ServerSocket immediately (synchronous, instant).
     * Call this BEFORE deploying the VD server so the socket is ready
     * when the VD server connects back.
     * Never throws: a busy port (zombie instance) must not crash the process —
     * the accept loop retries startListening each iteration.
     */
    fun startListening(port: Int = SERVER_PORT) {
        try {
            try { serverChannel?.close() } catch (_: Exception) {}
            val ch = ServerSocketChannel.open()
            ch.configureBlocking(false)
            ch.socket().reuseAddress = true
            ch.socket().bind(InetSocketAddress("0.0.0.0", port))
            serverChannel = ch
            FileLog.i(TAG, "Listening for VD server lifecycle on 0.0.0.0:$port")
        } catch (e: Exception) {
            FileLog.w(TAG, "Lifecycle bind on :$port failed — will retry: ${e.message}")
            serverChannel = null
        }
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
                FileLog.i(TAG, "Waiting for VD server lifecycle connection...")

                val deadline = System.currentTimeMillis() + timeoutMs
                var accepted: SocketChannel? = null
                while (isActive && System.currentTimeMillis() < deadline) {
                    accepted = ch.accept()
                    if (accepted != null) break
                    delay(50)
                }

                if (accepted == null) {
                    FileLog.w(TAG, "VD server did not connect within ${timeoutMs}ms")
                    return@withContext false
                }

                accepted.configureBlocking(false)
                accepted.socket().tcpNoDelay = true
                channel = accepted
                val rdr = NioReader(accepted, 65536)
                reader = rdr

                // Read MSG_DISPLAY_READY from VD
                val msgType = rdr.readByte()
                if (msgType == MSG_DISPLAY_READY) {
                    displayId = rdr.readInt()
                    val flags = rdr.readByte()
                    hasDirectInjection = (flags.toInt() and 1) != 0
                    isConnected = true
                    FileLog.i(TAG, "VD server connected, displayId=$displayId directInjection=$hasDirectInjection")
                    onDisplayReady?.invoke()
                    startCommandRelay()
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
            }
        }
    }

    /**
     * Reads non-video messages from the VD server (stack empty, focused app, shortcut results).
     */
    private fun startCommandRelay() {
        commandRelayJob = scope.launch(Dispatchers.IO) {
            val rdr = reader ?: return@launch
            FileLog.i(TAG, "Command relay started")
            try {
                while (isActive && isConnected) {
                    val msgType = rdr.readByte()

                    when (msgType) {
                        MSG_STACK_EMPTY -> {
                            FileLog.i(TAG, "VD stack empty")
                            onStackEmpty?.invoke()
                        }
                        MSG_FOCUSED_APP -> {
                            val pkgLen = rdr.readInt()
                            val pkgBytes = ByteArray(pkgLen)
                            rdr.readFully(pkgBytes, 0, pkgLen)
                            val focusedPkg = String(pkgBytes, Charsets.UTF_8)
                            FileLog.i(TAG, "VD focused app: $focusedPkg")
                            onFocusedApp?.invoke(focusedPkg)
                        }
                        MSG_SHORTCUTS_RESULT -> {
                            val pkgLen = rdr.readInt()
                            val pkgBytes = ByteArray(pkgLen)
                            rdr.readFully(pkgBytes, 0, pkgLen)
                            val pkg = String(pkgBytes, Charsets.UTF_8)
                            val dataLen = rdr.readInt()
                            val dataBytes = ByteArray(dataLen)
                            rdr.readFully(dataBytes, 0, dataLen)
                            val data = String(dataBytes, Charsets.UTF_8)
                            FileLog.i(TAG, "Shortcut result for $pkg: ${data.length} chars")
                            shortcutResponses.remove(pkg)?.complete(data)
                        }
                        else -> {
                            FileLog.w(TAG, "Unknown VD msg type: 0x${msgType.toString(16)}")
                        }
                    }
                }
            } catch (e: Exception) {
                FileLog.e(TAG, "Command relay error", e)
                isConnected = false
            }
        }
    }

    /**
     * Query app shortcuts via the VD server (which has shell access).
     * Returns the raw output from "cmd shortcut get-shortcuts" or null on timeout/failure.
     */
    suspend fun queryShortcuts(packageName: String): String? {
        val ch = channel ?: return null
        val deferred = CompletableDeferred<String>()
        shortcutResponses[packageName] = deferred
        try {
            val buf = ByteBuffer.allocate(1024)
            val bytes = packageName.toByteArray(Charsets.UTF_8)
            buf.put(CMD_QUERY_SHORTCUTS.toByte())
            buf.putInt(bytes.size)
            buf.put(bytes)
            buf.flip()
            synchronized(writeLock) {
                FrameCodec.writeAll(ch, buf)
            }
            return withTimeout(5000L) { deferred.await() }
        } catch (e: Exception) {
            FileLog.w(TAG, "VD shortcut query failed for $packageName: ${e.message}")
            return null
        } finally {
            shortcutResponses.remove(packageName)
        }
    }

    /** Send CMD_STOP to the VD server to trigger graceful shutdown */
    fun stopVdServer() {
        val ch = channel ?: return
        try {
            synchronized(writeLock) {
                writeBuf.clear()
                writeBuf.put(CMD_STOP.toByte())
                writeBuf.flip()
                FrameCodec.writeAll(ch, writeBuf)
            }
            FileLog.i(TAG, "Sent CMD_STOP to VD server")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to send CMD_STOP: ${e.message}")
        }
    }

    /** Send CMD_LAUNCH_APP to VD server to launch an app on the virtual display */
    fun launchAppOnVd(packageName: String) {
        val ch = channel ?: return
        try {
            val bytes = packageName.toByteArray(Charsets.UTF_8)
            synchronized(writeLock) {
                writeBuf.clear()
                writeBuf.put(CMD_LAUNCH_APP.toByte())
                writeBuf.putInt(bytes.size)
                writeBuf.put(bytes)
                writeBuf.flip()
                FrameCodec.writeAll(ch, writeBuf)
            }
            FileLog.i(TAG, "Sent CMD_LAUNCH_APP: $packageName")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to send CMD_LAUNCH_APP: ${e.message}")
        }
    }

    /** Send CMD_GO_HOME to VD server */
    fun goHomeOnVd() {
        val ch = channel ?: return
        try {
            synchronized(writeLock) {
                writeBuf.clear()
                writeBuf.put(CMD_GO_HOME.toByte())
                writeBuf.flip()
                FrameCodec.writeAll(ch, writeBuf)
            }
            FileLog.i(TAG, "Sent CMD_GO_HOME")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to send CMD_GO_HOME: ${e.message}")
        }
    }

    /** Send CMD_GO_BACK to VD server */
    fun goBackOnVd() {
        val ch = channel ?: return
        try {
            synchronized(writeLock) {
                writeBuf.clear()
                writeBuf.put(CMD_GO_BACK.toByte())
                writeBuf.flip()
                FrameCodec.writeAll(ch, writeBuf)
            }
            FileLog.i(TAG, "Sent CMD_GO_BACK")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to send CMD_GO_BACK: ${e.message}")
        }
    }

    fun disconnect() {
        isConnected = false
        commandRelayJob?.cancel()
        shortcutResponses.values.forEach { try { it.complete("") } catch (_: Exception) {} }
        shortcutResponses.clear()
        reader?.close()
        try { serverChannel?.close() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        serverChannel = null
        channel = null
        reader = null
        displayId = -1
        FileLog.i(TAG, "Disconnected from VD server lifecycle channel")
    }

    companion object {
        private const val TAG = "VirtualDisplayClient"
        const val SERVER_PORT = 19647

        // Must match VirtualDisplayServer constants
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11
        private const val MSG_FOCUSED_APP: Byte = 0x12
        private const val MSG_SHORTCUTS_RESULT: Byte = 0x13

        private const val CMD_QUERY_SHORTCUTS = 0x25
        private const val CMD_LAUNCH_APP = 0x30
        private const val CMD_GO_HOME = 0x31
        private const val CMD_GO_BACK = 0x32
        private const val CMD_STOP = 0xFF
    }
}
