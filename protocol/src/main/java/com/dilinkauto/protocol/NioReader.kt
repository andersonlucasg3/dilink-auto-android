package com.dilinkauto.protocol

import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.coroutines.coroutineContext

/**
 * Non-blocking buffered reader for a NIO SocketChannel.
 * Accumulates data from non-blocking reads and provides typed read methods.
 *
 * Uses a Selector for zero-latency wakeup when data arrives — no polling delay.
 * Cooperates with coroutine cancellation via periodic isActive checks.
 */
class NioReader(
    private val channel: SocketChannel,
    initialCapacity: Int = DEFAULT_CAPACITY,
    private val selectTimeoutMs: Long = VideoConfig.FRAME_INTERVAL_MS
) {

    init {
        require(!channel.isBlocking) { "Channel must be in non-blocking mode" }
    }

    private val selector: Selector = Selector.open()
    private val selectionKey: SelectionKey = channel.register(selector, SelectionKey.OP_READ)

    private var buf: ByteBuffer = ByteBuffer.allocate(initialCapacity).apply {
        order(ByteOrder.BIG_ENDIAN)
        flip() // empty, ready for reading
    }

    suspend fun readByte(): Byte { ensureAvailable(1); return buf.get() }
    suspend fun readInt(): Int { ensureAvailable(4); return buf.getInt() }
    suspend fun readFloat(): Float { ensureAvailable(4); return buf.getFloat() }

    suspend fun readFully(dst: ByteArray, offset: Int = 0, length: Int = dst.size) {
        ensureAvailable(length)
        buf.get(dst, offset, length)
    }

    /**
     * Read a byte, returning null on EOF. Use at message boundaries
     * to distinguish clean disconnect from mid-message EOF.
     */
    suspend fun readByteOrNull(): Byte? {
        if (!fillOrEof(1)) return null
        return buf.get()
    }

    /** Read an int, returning null on EOF. */
    suspend fun readIntOrNull(): Int? {
        if (!fillOrEof(4)) return null
        return buf.getInt()
    }

    /**
     * Ensures at least [count] bytes are available for reading.
     * Grows the internal buffer if necessary.
     * @throws IOException on EOF.
     */
    private suspend fun ensureAvailable(count: Int) {
        if (!fillOrEof(count)) {
            throw IOException("Channel closed: expected $count bytes")
        }
    }

    /**
     * Fill the buffer until at least [needed] bytes are available.
     * Uses Selector.select() for instant wakeup when data arrives (no polling delay).
     * Returns false on EOF, true when enough data is ready.
     */
    private suspend fun fillOrEof(needed: Int): Boolean {
        // Grow buffer if a single read unit exceeds capacity
        if (needed > buf.capacity()) {
            val newBuf = ByteBuffer.allocate(needed + GROW_PADDING)
            newBuf.order(ByteOrder.BIG_ENDIAN)
            newBuf.put(buf) // copy remaining unread data
            newBuf.flip()
            buf = newBuf
        }
        // Read from channel until we have enough
        while (buf.remaining() < needed) {
            buf.compact()
            val n = channel.read(buf)
            buf.flip()
            if (n == -1) return false
            if (n == 0) {
                // No data available — wait for data using Selector (instant wakeup)
                noDataCount++
                if (noDataCount % 100 == 1L) {
                    android.util.Log.d("NioReader", "no data #$noDataCount: needed=$needed remaining=${buf.remaining()} capacity=${buf.capacity()}")
                }
                yield() // cooperate with coroutine cancellation
                if (!coroutineContext.isActive || !selector.isOpen) return false
                selector.select(selectTimeoutMs) // blocks thread until data or timeout
                if (selector.isOpen) selector.selectedKeys().clear()
            }
        }
        return true
    }

    /**
     * Wakes the selector from another thread (e.g., during disconnect).
     * This unblocks any select() call in fillOrEof().
     */
    fun wakeup() {
        try { selector.wakeup() } catch (_: Exception) {}
    }

    fun close() {
        try { selector.wakeup() } catch (_: Exception) {}
        try { selectionKey.cancel() } catch (_: Exception) {}
        try { selector.close() } catch (_: Exception) {}
    }

    private var noDataCount = 0L

    companion object {
        const val DEFAULT_CAPACITY = 262144 // 256KB — matches socket receive buffer
        private const val GROW_PADDING = 4096
    }
}
