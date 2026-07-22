package com.dilinkauto.client.auto

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.FrameCodec
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Video channel server for the AA flow: the daemon connects out to
 * localhost:9638 and pushes H.264 (6B frame header + annex-B payload).
 * Decoded with platform MediaCodec straight into the AA host surface.
 */
class AaVideoClient(
    private val surface: Surface,
    private val width: Int,
    private val height: Int
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    fun bind(port: Int = PORT) {
        serverSocket = ServerSocket(port)
    }

    fun start() {
        running = true
        thread = thread(name = "AaVideo") { run() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        // Closing the active socket unblocks FrameCodec.readFrame in the loop
        try { activeSocket?.close() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { thread?.join(1000) } catch (_: Exception) {}
        thread = null
    }

    private fun run() {
        var socket: Socket? = null
        try {
            val srv = serverSocket ?: return
            socket = srv.accept()
            activeSocket = socket
            FileLog.i(TAG, "Daemon video channel connected")
            val input = socket.getInputStream()
            val info = MediaCodec.BufferInfo()
            var pendingConfig: ByteArray? = null

            while (running) {
                val frame = try {
                    FrameCodec.readFrame(input)
                } catch (e: Exception) {
                    FileLog.w(TAG, "Video read failed: ${e.message}")
                    break
                } ?: break
                if (frame.channel != CHANNEL_VIDEO) continue

                when (frame.messageType) {
                    VIDEO_CONFIG -> {
                        if (codec == null) {
                            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            val format = MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                            c.configure(format, surface, null, 0)
                            c.start()
                            codec = c
                            FileLog.i(TAG, "Decoder configured ${width}x${height}")
                        }
                        pendingConfig = frame.payload
                    }
                    VIDEO_FRAME -> {
                        val c = codec ?: continue
                        feed(c, pendingConfig, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        pendingConfig = null
                        feed(c, frame.payload,
                            if (isKeyFrame(frame.payload)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        drain(c, info)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) FileLog.e(TAG, "Video loop failed", e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            FileLog.i(TAG, "Video client exited")
        }
    }

    private fun feed(codec: MediaCodec, data: ByteArray?, flags: Int) {
        if (data == null || data.isEmpty()) return
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            if (buf.remaining() < data.size) {
                FileLog.w(TAG, "Frame larger than input buffer (${data.size}B) — dropped")
                return
            }
            buf.put(data)
            // pts=0 renders as fast as frames arrive (daemon already paces at fps)
            codec.queueInputBuffer(idx, 0, data.size, 0, flags)
        } catch (e: Exception) {
            if (running) FileLog.w(TAG, "Feed failed: ${e.message}")
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running) {
            when (val idx = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    FileLog.i(TAG, "Decoder output format: ${codec.outputFormat}")
                else -> codec.releaseOutputBuffer(idx, true)
            }
        }
    }

    private fun isKeyFrame(payload: ByteArray): Boolean {
        // Annex B: find first start code (3 or 4 bytes); NAL header type 5 = IDR
        var i = 0
        while (i + 4 < payload.size && i < 8) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() &&
                (payload[i + 2] == 1.toByte() ||
                    (payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()))) {
                val nal = if (payload[i + 2] == 1.toByte()) payload[i + 3] else payload[i + 4]
                return nal.toInt() and 0x1F == 5
            }
            i++
        }
        return false
    }

    private companion object {
        private const val TAG = "AaVideoClient"
        const val PORT = 9638
        private const val CHANNEL_VIDEO: Byte = 1
        private const val VIDEO_CONFIG: Byte = 1
        private const val VIDEO_FRAME: Byte = 2
    }
}
