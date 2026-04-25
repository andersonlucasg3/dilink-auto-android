package com.dilinkauto.protocol

import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a multiplexed connection between client and server.
 * Handles frame reading/writing, heartbeats, and channel dispatch.
 *
 * All I/O is non-blocking NIO. Reads use NioReader (Selector-based).
 * Writes use a dedicated writer coroutine with a lock-free queue —
 * no synchronized blocks on the write path, no spin-waiting.
 */
class Connection(
    private val channel: SocketChannel,
    private val scope: CoroutineScope
) {
    private val actualSendBuf: Int
    private val actualRecvBuf: Int

    init {
        channel.configureBlocking(false)
        val sock = channel.socket()
        sock.sendBufferSize = 262144    // request 256KB
        sock.receiveBufferSize = 262144
        sock.tcpNoDelay = true
        sock.keepAlive = true
        actualSendBuf = sock.sendBufferSize   // what the kernel actually gave us
        actualRecvBuf = sock.receiveBufferSize
    }

    private val reader = NioReader(channel)
    private val connected = AtomicBoolean(true)

    // Write queue: lock-free enqueue from any thread, drained by dedicated writer coroutine.
    private val writeQueue = ConcurrentLinkedQueue<FrameCodec.Frame>()
    @Volatile private var writerThread: Thread? = null

    private val frameListeners = ConcurrentHashMap<Byte, (FrameCodec.Frame) -> Unit>()
    private var disconnectListener: (() -> Unit)? = null
    private var logListener: ((String) -> Unit)? = null
    private var disconnectReason: String = "unknown"

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastFrameReceivedAt = System.currentTimeMillis()

    val isConnected: Boolean get() = connected.get() && channel.isOpen

    /** Remote peer's IP address (e.g. for ADB connection to the car) */
    val remoteAddress: String? get() = try {
        (channel.remoteAddress as? InetSocketAddress)?.address?.hostAddress
    } catch (_: Exception) { null }

    /**
     * Starts reading frames, writing queued frames, heartbeats, and watchdog.
     */
    fun start(enableHeartbeat: Boolean = true) {
        log("Connection started: sendBuf=$actualSendBuf recvBuf=$actualRecvBuf tcpNoDelay=${channel.socket().tcpNoDelay} heartbeat=$enableHeartbeat")
        lastFrameReceivedAt = System.currentTimeMillis()

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && connected.get()) {
                    val frame = FrameCodec.readFrame(reader)
                    if (frame == null) {
                        disconnectReason = "reader: EOF"
                        log(disconnectReason)
                        disconnect()
                        break
                    }

                    lastFrameReceivedAt = System.currentTimeMillis()

                    if (frame.channel == Channel.CONTROL &&
                        frame.messageType == ControlMsg.HEARTBEAT
                    ) {
                        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT_ACK, ByteArray(0)))
                        continue
                    }

                    // Video frames: handle inline for low latency.
                    // Other frames (data, control): dispatch async so heavy processing
                    // (e.g., app list decode) doesn't block the reader from draining TCP.
                    val listener = frameListeners[frame.channel]
                    if (listener != null) {
                        if (frame.channel == Channel.VIDEO) {
                            listener.invoke(frame)
                        } else {
                            scope.launch { listener.invoke(frame) }
                        }
                    }
                }
            } catch (e: IOException) {
                disconnectReason = "reader: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            } catch (e: ProtocolException) {
                disconnectReason = "reader: ProtocolException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            }
        }

        // Dedicated writer coroutine: drains the write queue.
        // Uses Selector for write-readiness when TCP send buffer is full.
        // No deadline — the watchdog handles dead connections.
        writerJob = scope.launch(Dispatchers.IO) {
            val headerBuf = ByteArray(FrameCodec.HEADER_SIZE)
            var writeCount = 0L
            try {
                while (isActive && connected.get()) {
                    writerThread = Thread.currentThread()
                    val frame = writeQueue.poll()
                    if (frame == null) {
                        java.util.concurrent.locks.LockSupport.parkNanos(VideoConfig.FRAME_INTERVAL_MS * 1_000_000L)
                        continue
                    }

                    // Encode header
                    val frameLength = 2 + frame.payload.size
                    headerBuf[0] = (frameLength shr 24).toByte()
                    headerBuf[1] = (frameLength shr 16).toByte()
                    headerBuf[2] = (frameLength shr 8).toByte()
                    headerBuf[3] = frameLength.toByte()
                    headerBuf[4] = frame.channel
                    headerBuf[5] = frame.messageType

                    // Gathering write: header + payload in single syscall
                    val bufs = if (frame.payload.isNotEmpty()) {
                        arrayOf(ByteBuffer.wrap(headerBuf), ByteBuffer.wrap(frame.payload))
                    } else {
                        arrayOf(ByteBuffer.wrap(headerBuf))
                    }
                    writeBuffersToChannel(bufs)
                    writeCount++
                    if (frame.channel == Channel.VIDEO && writeCount % 60 == 0L) {
                        log("writer: frame #$writeCount ch=${frame.channel} size=${frame.payload.size} queue=${writeQueue.size} stalls=$writeStallCount")
                    }
                }
            } catch (e: java.nio.channels.ClosedSelectorException) {
                // Selector closed by disconnect() — normal shutdown
            } catch (e: IOException) {
                disconnectReason = "writer: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            }
        }

        if (enableHeartbeat) {
            heartbeatJob = scope.launch(Dispatchers.IO) {
                while (isActive && connected.get()) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!connected.get()) break
                    try {
                        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT, ByteArray(0)))
                    } catch (e: IOException) {
                        disconnectReason = "heartbeat: IOException: ${e.message}"
                        log(disconnectReason)
                        disconnect()
                        break
                    }
                }
            }

            watchdogJob = scope.launch(Dispatchers.IO) {
                while (isActive && connected.get()) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    val elapsed = System.currentTimeMillis() - lastFrameReceivedAt
                    if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                        disconnectReason = "watchdog: no frame received for ${elapsed}ms (timeout=${HEARTBEAT_TIMEOUT_MS}ms)"
                        log(disconnectReason)
                        disconnect()
                        break
                    }
                }
            }
        }
    }

    /**
     * Writes all bytes from multiple buffers to the channel using gathering write.
     * When TCP send buffer is full, yields briefly then retries — the writer is a
     * dedicated coroutine so busy-waiting is acceptable and matches v0.6.2 behavior
     * (blocking OutputStream.write that retried immediately when buffer drained).
     * No Selector OP_WRITE — the 100ms Selector timeout was causing stalls.
     */

    @Volatile private var writeStallCount = 0L

    private suspend fun writeBuffersToChannel(bufs: Array<ByteBuffer>) {
        while (bufs.any { it.hasRemaining() }) {
            val n = channel.write(bufs)
            if (n > 0) continue
            if (!connected.get()) throw IOException("Connection closed during write")
            writeStallCount++
            if (writeStallCount % 100 == 1L) {
                val remaining = bufs.sumOf { it.remaining().toLong() }
                log("write stall #$writeStallCount: remaining=$remaining queue=${writeQueue.size}")
            }
            delay(1)
        }
    }

    /**
     * Enqueues a frame for writing. Non-blocking, lock-free.
     */
    private fun enqueueFrame(frame: FrameCodec.Frame) {
        if (!connected.get()) throw IOException("Not connected")
        writeQueue.add(frame)
        val wt = writerThread
        if (wt != null) java.util.concurrent.locks.LockSupport.unpark(wt)
    }

    fun onFrames(channel: Byte, listener: (FrameCodec.Frame) -> Unit) {
        frameListeners[channel] = listener
    }

    fun onDisconnect(listener: () -> Unit) {
        disconnectListener = listener
    }

    fun onLog(listener: (String) -> Unit) {
        logListener = listener
    }

    private fun log(msg: String) {
        logListener?.invoke(msg)
    }

    fun sendFrame(frame: FrameCodec.Frame) {
        enqueueFrame(frame)
    }

    fun sendControl(messageType: Byte, payload: ByteArray = ByteArray(0)) {
        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, messageType, payload))
    }

    fun sendVideo(messageType: Byte, payload: ByteArray) {
        enqueueFrame(FrameCodec.Frame(Channel.VIDEO, messageType, payload))
    }

    fun sendAudio(messageType: Byte, payload: ByteArray) {
        enqueueFrame(FrameCodec.Frame(Channel.AUDIO, messageType, payload))
    }

    fun sendData(messageType: Byte, payload: ByteArray) {
        enqueueFrame(FrameCodec.Frame(Channel.DATA, messageType, payload))
    }

    fun sendInput(messageType: Byte, payload: ByteArray) {
        enqueueFrame(FrameCodec.Frame(Channel.INPUT, messageType, payload))
    }

    fun disconnect() {
        if (connected.compareAndSet(true, false)) {
            val caller = if (disconnectReason == "unknown") {
                val trace = Thread.currentThread().stackTrace
                val relevant = trace.drop(2).take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}:${it.methodName}:${it.lineNumber}" }
                "external call: $relevant"
            } else disconnectReason
            log("disconnect() reason=$caller")
            readerJob?.cancel()
            writerJob?.cancel()
            heartbeatJob?.cancel()
            watchdogJob?.cancel()
            reader.close()
            val wt = writerThread
            if (wt != null) java.util.concurrent.locks.LockSupport.unpark(wt)
            try { channel.close() } catch (_: Exception) {}
            try { disconnectListener?.invoke() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L

        suspend fun connect(host: String, port: Int, scope: CoroutineScope): Connection =
            withContext(Dispatchers.IO) {
                val channel = SocketChannel.open()
                try {
                    channel.configureBlocking(false)
                    channel.connect(InetSocketAddress(host, port))
                    while (!channel.finishConnect()) {
                        delay(50)
                    }
                    Connection(channel, scope)
                } catch (e: Exception) {
                    try { channel.close() } catch (_: Exception) {}
                    throw e
                }
            }

        suspend fun accept(
            port: Int,
            scope: CoroutineScope
        ): Connection = withContext(Dispatchers.IO) {
            val channel = ServerSocketChannel.open()
            channel.configureBlocking(false)
            channel.socket().reuseAddress = true
            channel.socket().bind(InetSocketAddress(port))
            try {
                while (isActive) {
                    val accepted = channel.accept()
                    if (accepted != null) {
                        return@withContext Connection(accepted, scope)
                    }
                    delay(100)
                }
                throw java.util.concurrent.CancellationException("Cancelled while accepting")
            } finally {
                channel.close()
            }
        }
    }
}
