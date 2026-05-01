package com.dilinkauto.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.LockSupport

/**
 * Binary frame codec for the DiLink-Auto protocol.
 *
 * Frame format (big-endian):
 * ┌─────────────────────────────────────────┐
 * │ Frame Length (4 bytes, uint32)           │  Total frame size excluding this field
 * │ Channel ID  (1 byte)                    │  See [Channel]
 * │ Message Type (1 byte)                   │  See message type objects
 * │ Payload     (N bytes)                   │  Message-specific data
 * └─────────────────────────────────────────┘
 *
 * Maximum payload size: 2 MB (sufficient for H.264 keyframes at 1080p)
 */
object FrameCodec {

    const val HEADER_SIZE = 6 // 4 (length) + 1 (channel) + 1 (type)
    const val MAX_PAYLOAD_SIZE = 128 * 1024 * 1024 // 128 MB

    data class Frame(
        val channel: Byte,
        val messageType: Byte,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return channel == other.channel &&
                    messageType == other.messageType &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = channel.toInt()
            result = 31 * result + messageType.toInt()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Encodes a frame and writes it to the output stream.
     * Thread-safe if the output stream is synchronized externally.
     */
    // Reusable header buffer — avoids 6-byte allocation per frame (30x/sec)
    private val headerLocal = ThreadLocal.withInitial { ByteArray(HEADER_SIZE) }

    fun writeFrame(out: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload too large: ${frame.payload.size} > $MAX_PAYLOAD_SIZE"
        }

        val frameLength = 2 + frame.payload.size
        val header = headerLocal.get()!!
        header[0] = (frameLength shr 24).toByte()
        header[1] = (frameLength shr 16).toByte()
        header[2] = (frameLength shr 8).toByte()
        header[3] = frameLength.toByte()
        header[4] = frame.channel
        header[5] = frame.messageType

        out.write(header)
        out.write(frame.payload)
        // No flush here — caller decides when to flush (video batches, control flushes immediately)
    }

    /**
     * Reads a complete frame from the input stream.
     * Blocks until a full frame is available or the stream ends.
     *
     * @return The decoded frame, or null if the stream ended cleanly.
     * @throws ProtocolException if the frame is malformed.
     */
    fun readFrame(input: InputStream): Frame? {
        // Read frame length (4 bytes)
        val lengthBuf = readExact(input, 4) ?: return null
        val frameLength = ByteBuffer.wrap(lengthBuf)
            .order(ByteOrder.BIG_ENDIAN)
            .getInt()

        if (frameLength < 2) {
            throw ProtocolException("Frame too small: $frameLength")
        }
        if (frameLength - 2 > MAX_PAYLOAD_SIZE) {
            throw ProtocolException("Frame payload too large: ${frameLength - 2}")
        }

        // Read channel + type (2 bytes)
        val chType = readExact(input, 2)
            ?: throw ProtocolException("Unexpected end of stream: missing channel/type")

        // Read payload directly — no intermediate buffer + copyOfRange
        val payloadSize = frameLength - 2
        val payload = if (payloadSize > 0) {
            readExact(input, payloadSize)
                ?: throw ProtocolException("Unexpected end of stream: expected $payloadSize bytes")
        } else ByteArray(0)

        return Frame(
            channel = chType[0],
            messageType = chType[1],
            payload = payload
        )
    }

    /**
     * Reads exactly [count] bytes from the input stream.
     * Returns null only if the stream ends before ANY bytes are read (clean disconnect).
     * Throws ProtocolException if the stream ends mid-read.
     */
    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) {
                if (offset == 0) return null
                throw ProtocolException("Unexpected end of stream: expected $count bytes, got $offset")
            }
            offset += read
        }
        return buf
    }

    // ─── NIO Channel Methods ───

    /**
     * Read a frame from a non-blocking NIO reader. Returns null on EOF.
     */
    suspend fun readFrame(reader: NioReader): Frame? {
        val frameLength = reader.readIntOrNull() ?: return null

        if (frameLength < 2) {
            throw ProtocolException("Frame too small: $frameLength")
        }
        val payloadSize = frameLength - 2
        if (payloadSize > MAX_PAYLOAD_SIZE) {
            throw ProtocolException("Frame payload too large: $payloadSize > $MAX_PAYLOAD_SIZE")
        }

        val channelId = reader.readByte()
        val msgType = reader.readByte()

        // Log large frames (potential corruption indicator)
        if (payloadSize > 100_000) {
            android.util.Log.w("FrameCodec", "Large frame: ch=$channelId type=0x${msgType.toString(16)} payload=$payloadSize bytes")
        }

        val payload = if (payloadSize > 0) {
            ByteArray(payloadSize).also { reader.readFully(it) }
        } else ByteArray(0)

        return Frame(channelId, msgType, payload)
    }

    /**
     * Write a frame to a non-blocking NIO SocketChannel.
     * Uses ThreadLocal header buffer + zero-copy payload wrap.
     * Thread-safe if synchronized externally.
     */
    fun writeFrameToChannel(channel: SocketChannel, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload too large: ${frame.payload.size} > $MAX_PAYLOAD_SIZE"
        }

        val frameLength = 2 + frame.payload.size
        val header = headerLocal.get()!!
        header[0] = (frameLength shr 24).toByte()
        header[1] = (frameLength shr 16).toByte()
        header[2] = (frameLength shr 8).toByte()
        header[3] = frameLength.toByte()
        header[4] = frame.channel
        header[5] = frame.messageType

        writeAll(channel, ByteBuffer.wrap(header))
        if (frame.payload.isNotEmpty()) {
            writeAll(channel, ByteBuffer.wrap(frame.payload))
        }
    }

    private const val WRITE_TIMEOUT_NS = 5_000_000_000L // 5 seconds

    /**
     * Writes all remaining bytes from buf to channel.
     * Throws IOException if no progress is made for 5 seconds (send buffer full / peer not reading).
     */
    @JvmStatic
    fun writeAll(channel: SocketChannel, buf: ByteBuffer) {
        var deadline = System.nanoTime() + WRITE_TIMEOUT_NS
        while (buf.hasRemaining()) {
            val n = channel.write(buf)
            if (n > 0) {
                deadline = System.nanoTime() + WRITE_TIMEOUT_NS // reset on progress
            } else {
                if (System.nanoTime() > deadline) {
                    throw IOException("Write timed out: ${buf.remaining()} bytes remaining, send buffer full for 5s")
                }
                LockSupport.parkNanos(100_000) // 100us — prevents tight spin on full buffer
            }
        }
    }
}

class ProtocolException(message: String) : Exception(message)
