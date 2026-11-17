package com.dilinkauto.vdserver

import com.dilinkauto.protocol.FrameCodec
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Single-writer queue for the lifecycle channel (audit R3-SRP-10).
 *
 * The lifecycle Channel is registered for OP_READ by [NioReader]; toggling its
 * blocking mode to write synchronously throws `IllegalBlockingModeException`,
 * which the old try/catch silently swallowed — so every MSG_STACK_EMPTY was
 * dropped. Dedicated writer thread sends responses asynchronously using [FrameCodec.writeAll]
 * without touching the channel's blocking mode or spin sleeping with Thread.sleep(1).
 *
 * Extracted from [PipelineServer], which keeps only the wiring.
 *
 * @param running polled when enqueuing; when it flips false new buffers are rejected.
 */
internal class LifecycleWriter(
    private val running: () -> Boolean = { true },
    capacity: Int = DEFAULT_CAPACITY,
    private val writeBuffer: ((ByteBuffer) -> Unit)? = null
) : AutoCloseable {

    /** Set once the phone-side lifecycle channel is accepted. */
    @Volatile var channel: SocketChannel? = null

    private val sender = AsyncSocketSender<ByteBuffer>(
        capacity = capacity,
        threadName = "LifeWriter",
        writeItem = { buf ->
            if (writeBuffer != null) {
                writeBuffer.invoke(buf)
            } else {
                val ch = channel
                if (ch != null && ch.isOpen) {
                    try {
                        synchronized(ch) {
                            FrameCodec.writeAll(ch, buf)
                        }
                    } catch (_: Exception) {
                        // Swallowed: lifecycle responses are advisory (stack-empty).
                        // Dropping one on a transient I/O error is preferable to crashing the
                        // pipeline — which is what toggling blocking mode to write would do.
                    }
                }
            }
        },
        closeChannel = {},
        onWriteFailed = {}
    )

    fun start() = sender.start()

    /**
     * Enqueue a response. Layout: bare `msgType` byte when the payload is empty,
     * otherwise `msgType` + int length + payload. Drops silently if the queue is full.
     */
    fun enqueue(msgType: Byte, payload: ByteArray) {
        if (!running()) return
        try {
            val len = if (payload.isEmpty()) 1 else 5 + payload.size
            val buf = ByteBuffer.allocate(len)
            buf.put(msgType)
            if (payload.isNotEmpty()) { buf.putInt(payload.size); buf.put(payload) }
            buf.flip()
            sender.enqueue(buf) {
                // Advisory message: drop if queue is full
                false
            }
        } catch (_: Exception) {}
    }

    /** Interrupt the writer so it leaves await() before the shell/VD teardown. */
    fun stop() = close()

    override fun close() {
        sender.close()
    }

    val queued: Int get() = sender.queued

    companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
