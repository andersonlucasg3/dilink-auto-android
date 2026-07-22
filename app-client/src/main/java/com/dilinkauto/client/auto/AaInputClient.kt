package com.dilinkauto.client.auto

import android.os.SystemClock
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.FrameCodec
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Input channel server for the AA flow: the daemon connects out to
 * localhost:9639. Encodes TouchEvent frames (25B payload, wire-compatible
 * with native-shared/protocol.h). The daemon injects `input tap` on DOWN.
 */
class AaInputClient {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val writeLock = Any()

    fun bind(port: Int = PORT) {
        serverSocket = ServerSocket(port)
    }

    fun start() {
        thread(name = "AaInput") {
            try {
                val s = serverSocket?.accept() ?: return@thread
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                FileLog.i(TAG, "Daemon input channel connected")
            } catch (e: Exception) {
                FileLog.w(TAG, "Input accept failed: ${e.message}")
            }
        }
    }

    /** Inject a tap at normalized coordinates (0.0–1.0 relative to the VD). */
    fun tap(xNorm: Float, yNorm: Float) {
        val stream = out ?: return
        val payload = ByteBuffer.allocate(25).apply {
            // action byte carries the msg_type, matching encode_touch_event()
            put(INPUT_TOUCH_DOWN)
            putInt(0)                                // pointer id
            putFloat(xNorm.coerceIn(0f, 1f))
            putFloat(yNorm.coerceIn(0f, 1f))
            putFloat(1.0f)                           // pressure
            putLong(SystemClock.uptimeMillis())
        }.array()
        try {
            synchronized(writeLock) {
                FrameCodec.writeFrame(stream, FrameCodec.Frame(CHANNEL_INPUT, INPUT_TOUCH_DOWN, payload))
                stream.flush()
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "Tap send failed: ${e.message}")
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        socket = null
        serverSocket = null
        out = null
    }

    private companion object {
        private const val TAG = "AaInputClient"
        const val PORT = 9639
        private const val CHANNEL_INPUT: Byte = 4
        private const val INPUT_TOUCH_DOWN: Byte = 1
    }
}
