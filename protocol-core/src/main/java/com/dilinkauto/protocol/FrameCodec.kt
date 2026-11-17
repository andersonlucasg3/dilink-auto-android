package com.dilinkauto.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

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
 * Maximum payload size: [MAX_PAYLOAD_SIZE] — a defensive cap, not a target.
 * Real payloads top out at ~1-2 MB (H.264 keyframes at 1080p). This is separate
 * from ADB's 256 KB transfer limit (`AdbProtocol.MAX_PAYLOAD`), which only
 * applies inside the ADB stream, not to this TCP framing.
 *
 * 128 MB was far above anything the stream legitimately carries while letting a
 * 7-byte malicious header force ~256 MB of allocation (payload ByteArray plus
 * the NioReader's grow-on-demand buffer) on the phone and on the shell-UID
 * vd-server. 16 MB leaves 8x headroom over a 1080p keyframe and makes the same
 * attack a 32 MB event that both sides survive.
 */
object FrameCodec {

    const val HEADER_SIZE = 6 // 4 (length) + 1 (channel) + 1 (type)
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16 MB

    /** Frames above this size log a warning; a 1080p keyframe is ~1 MB. */
    private const val LARGE_FRAME_LOG_THRESHOLD = 8 * 1024 * 1024

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

    /**
     * Encode a frame's 6-byte header into [buf]. Shared by [writeFrame],
     * [writeFrameToChannel] and the Connection write coroutine so the byte
     * layout lives in one place.
     */
    internal fun encodeHeaderInto(buf: ByteArray, frame: Frame) {
        val frameLength = 2 + frame.payload.size
        buf[0] = (frameLength shr 24).toByte()
        buf[1] = (frameLength shr 16).toByte()
        buf[2] = (frameLength shr 8).toByte()
        buf[3] = frameLength.toByte()
        buf[4] = frame.channel
        buf[5] = frame.messageType
    }

    /**
     * Validate a frame length and return the payload size, or throw if malformed.
     * Shared by [readFrame] and [readFrameBlocking].
     */
    private fun validatePayloadSize(frameLength: Int): Int {
        if (frameLength < 2) {
            throw ProtocolException("Frame too small: $frameLength")
        }
        val payloadSize = frameLength - 2
        if (payloadSize > MAX_PAYLOAD_SIZE) {
            throw ProtocolException("Frame payload too large: $payloadSize > $MAX_PAYLOAD_SIZE")
        }
        return payloadSize
    }

    fun writeFrame(out: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload too large: ${frame.payload.size} > $MAX_PAYLOAD_SIZE"
        }

        val header = headerLocal.get()!!
        encodeHeaderInto(header, frame)

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
    fun readFrame(input: InputStream): Frame? = readFrameCore(
        readLength = { readFrameLength(input) },
        readBytes = { count -> readExact(input, count) }
    )

    /** Read the 4-byte big-endian frame length prefix, or null on clean EOF. */
    private fun readFrameLength(input: InputStream): Int? {
        val lengthBuf = readExact(input, 4) ?: return null
        return ByteBuffer.wrap(lengthBuf).order(ByteOrder.BIG_ENDIAN).getInt()
    }

    /**
     * Shared skeleton of the two blocking read paths ([readFrame] and
     * [readFrameBlocking]): decode the length prefix, validate it, read the
     * 2-byte channel/type pair and the payload through the caller's primitive
     * readers, then assemble the frame.
     *
     * The suspend [readFrame] variant cannot share this core — Kotlin forbids
     * invoking suspend lambdas from a blocking caller — so it reuses
     * [validatePayloadSize] and [finishFrame] directly instead.
     *
     * @param readLength returns the 4-byte frame length, or null on clean EOF
     * @param readBytes reads exactly N bytes, or returns null on EOF
     */
    private fun readFrameCore(readLength: () -> Int?, readBytes: (Int) -> ByteArray?): Frame? {
        val frameLength = readLength() ?: return null
        val payloadSize = validatePayloadSize(frameLength)

        val chType = readBytes(2)
            ?: throw ProtocolException("Unexpected end of stream: missing channel/type")
        val payload = if (payloadSize > 0) readBytes(payloadSize) else null
        return finishFrame(payloadSize, chType[0], chType[1], payload)
    }

    /**
     * Shared tail of every read path: build the frame from the declared payload
     * size plus the channel/type and payload that were already read. A null
     * [payload] with a positive [payloadSize] means the stream ended mid-frame.
     */
    private fun finishFrame(payloadSize: Int, channelId: Byte, msgType: Byte, payload: ByteArray?): Frame {
        val data = if (payloadSize > 0) {
            payload ?: throw ProtocolException("Unexpected end of stream: expected $payloadSize bytes")
        } else ByteArray(0)
        return Frame(channelId, msgType, data)
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
     * Read a frame from a non-blocking NIO reader (blocking API, for non-coroutine callers).
     * Returns null on EOF.
     */
    fun readFrameBlocking(reader: NioReader): Frame? = readFrameCore(
        readLength = { reader.readIntOrNullBlocking() },
        readBytes = { count ->
            ByteArray(count).also {
                reader.readFullyBlocking(it)
                reader.maybeShrink()
            }
        }
    )
    /**
     * Read a frame from a non-blocking NIO reader. Returns null on EOF.
     *
     * Shares [validatePayloadSize] / [finishFrame] with the blocking paths; the
     * read sequence itself stays written out here because [NioReader]'s suspend
     * primitives cannot be passed into [readFrameCore] from a coroutine.
     */
    suspend fun readFrame(reader: NioReader): Frame? {
        val frameLength = reader.readIntOrNull() ?: return null
        val payloadSize = validatePayloadSize(frameLength)

        val channelId = reader.readByte()
        val msgType = reader.readByte()

        // A frame this large is legal (1080p keyframes reach ~1 MB) but a good
        // corruption indicator; log it once per stream instead of on every GOP.
        if (payloadSize > LARGE_FRAME_LOG_THRESHOLD) {
            PlatformLog.w("FrameCodec", "Large frame: ch=$channelId type=0x${msgType.toString(16)} payload=$payloadSize bytes")
        }

        val payload = if (payloadSize > 0) {
            ByteArray(payloadSize).also { reader.readFully(it) }
        } else null
        reader.maybeShrink()
        return finishFrame(payloadSize, channelId, msgType, payload)
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

        val header = headerLocal.get()!!
        encodeHeaderInto(header, frame)

        NioWriter.writeAll(channel, ByteBuffer.wrap(header))
        if (frame.payload.isNotEmpty()) {
            NioWriter.writeAll(channel, ByteBuffer.wrap(frame.payload))
        }
    }

    /**
     * 写侧「零进展」判死的等待上限与退避参数（统一收敛至 [NioWriter]）。
     */
    const val WRITE_TIMEOUT_NS = NioWriter.WRITE_TIMEOUT_NS
    const val WRITE_BACKOFF_NS = NioWriter.WRITE_BACKOFF_NS

    /**
     * Writes all remaining bytes from buf to channel.
     * 委托给 [NioWriter.writeAll] 以保持向后兼容。
     */
    @JvmStatic
    fun writeAll(channel: SocketChannel, buf: ByteBuffer) = NioWriter.writeAll(channel, buf)

    /**
     * [writeAll] 的可注入 deadline 版本：委托给 [NioWriter.writeAll]。
     */
    @JvmStatic
    internal fun writeAll(channel: SocketChannel, buf: ByteBuffer, timeoutNs: Long) =
        NioWriter.writeAll(channel, buf, timeoutNs)
}

class ProtocolException(message: String) : Exception(message)
