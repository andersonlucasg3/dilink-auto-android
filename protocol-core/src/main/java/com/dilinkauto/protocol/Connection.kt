package com.dilinkauto.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a multiplexed connection between client and server.
 * Handles frame reading/writing, heartbeats, and channel dispatch.
 *
 * All I/O is non-blocking NIO. Reads use NioReader (Selector-based).
 * Writes use a dedicated writer coroutine with a non-blocking coroutine Channel —
 * no synchronized blocks on the write path, no spin-waiting.
 */
class Connection(
    private val channel: SocketChannel,
    private val scope: CoroutineScope,
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS
) {
    private val actualSendBuf: Int
    private val actualRecvBuf: Int

    init {
        channel.configureBlocking(false)
        val sock = channel.socket()
        sock.sendBufferSize = SOCKET_BUF_BYTES    // request 512KB（issue #70：显式设置即内核不再自动调大，故这值就是硬上限）
        sock.receiveBufferSize = SOCKET_BUF_BYTES
        sock.tcpNoDelay = true
        sock.keepAlive = true
        actualSendBuf = sock.sendBufferSize   // what the kernel actually gave us
        actualRecvBuf = sock.receiveBufferSize
    }

    // The stall deadline covers connections without a heartbeat/watchdog (the
    // direct video and input sockets): a peer that connects, sends a partial
    // frame and goes silent no longer pins the reader forever. Idle-at-boundary
    // (touch link with no user input) is deliberately not timed out.
    private val reader = NioReader(
        channel = channel,
        readStallTimeoutMs = HEARTBEAT_TIMEOUT_MS
    )
    private val connected = AtomicBoolean(true)

    // Write queue: bounded coroutine channel, drained by dedicated writer coroutine.
    // Bounded (not UNLIMITED) so a slow TCP consumer applies backpressure to the
    // reader instead of growing the queue unbounded and OOMing on a slow car link.
    private val writeQueue = kotlinx.coroutines.channels.Channel<FrameCodec.Frame>(
        QUEUE_CAPACITY, kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )
    private val pendingSendCount = java.util.concurrent.atomic.AtomicInteger(0)
    val pendingSends: Int get() = pendingSendCount.get()

    /**
     * 发送入队的单线程派发器。
     *
     * 为什么需要它：`sendFrame` 是非挂起 API，会被 UI / 服务线程随时调用。若每帧各自
     * `scope.launch` 到多线程的 IO 池，多个协程会并发抢入队，帧的上线路顺序就不再等于
     * 调用顺序 —— 触摸 DOWN/MOVE/UP 乱序会让手机侧注入出错。
     *
     * 单线程派发器保证入队严格 FIFO：同一调用线程连续调用 `sendFrame` 时，调用顺序 ==
     * 上线路顺序。它只是 IO 池的一个受限视图，不额外占用线程，也无需释放。
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 入队互斥:limitedParallelism(1) 只串行化"派发",不串行化"挂起"。
     * 队列满时首个协程挂在 `writeQueue.send`,单线程派发器随即运行下一个协程,
     * 后帧反而先入队 —— 恰好在链路拥塞(注释声称要保护的场景)下乱序。
     * 用锁把"入队"这段临界区钉住:后到的协程阻塞在 acquire,先到的先入队。
     */
    private val sendMutex = Mutex()

    private val frameListeners = ConcurrentHashMap<Byte, (FrameCodec.Frame) -> Unit>()
    @Volatile private var disconnectListener: (() -> Unit)? = null
    @Volatile private var logListener: ((String) -> Unit)? = null
    @Volatile private var disconnectReason: String = "unknown"
    @Volatile private var activeWriteTimeoutMs: Long = writeTimeoutMs

    private var readerJob: Job? = null
    private var writerJob: Job? = null

    @Volatile
    private var lastFrameReceivedAt = System.currentTimeMillis()

    /**
     * 是否已经真正读到过一帧（issue #200 评审）。
     *
     * [lastFrameReceivedAt] 的初始值是**构造时刻**，`start()` 还会把它重置成启动时刻 ——
     * 两者都不代表对端发过任何东西。若拿这两个时刻当基线，「握手/VD 引擎启动慢于阈值」
     * 就会被读成「静默 N 秒 = 死链」，进而触发一次 3.7~4.9s 的主动重建，比现状更糟。
     * 所以未收到过帧时 [msSinceLastFrameReceived] 一律返回 -1（调用方按「无法判断」处理）。
     */
    @Volatile
    private var hasReceivedFrame = false

    private val keepAlive = ConnectionKeepAlive(
        scope = scope,
        isConnected = { connected.get() },
        getLastFrameReceivedAt = { lastFrameReceivedAt },
        sendHeartbeat = {
            enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT, ByteArray(0)))
        },
        onDisconnect = { reason ->
            disconnectReason = reason
            disconnect()
        },
        log = { msg -> log(msg) }
    )

    val isConnected: Boolean get() = connected.get() && channel.isOpen

    /**
     * 距离上一次读到任意帧（含心跳）已过去的毫秒数（issue #200）。
     *
     * 只读诊断量，不参与任何超时判定（看门狗仍走 [ConnectionKeepAlive]）。车机端用它
     * 区分「链路断」（控制帧一起停）与「编码停」（控制帧照常、只有视频不动）；
     * 手机端用它判断重关联后 socket 是否已经半死。
     *
     * @return 未收到过任何帧时返回 **-1**（构造/启动时刻不是「收到帧」的证据）
     */
    fun msSinceLastFrameReceived(): Long =
        if (!hasReceivedFrame) -1L
        else (System.currentTimeMillis() - lastFrameReceivedAt).coerceAtLeast(0L)

    /** Remote peer's IP address (e.g. for ADB connection to the car) */
    val remoteAddress: String? get() = try {
        (channel.remoteAddress as? InetSocketAddress)?.address?.hostAddress
    } catch (_: Exception) { null }

    /**
     * Starts reading frames, writing queued frames, heartbeats, and watchdog.
     */
    fun start(
        enableHeartbeat: Boolean = true,
        heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
        heartbeatTimeoutMs: Long = HEARTBEAT_TIMEOUT_MS,
        writeTimeoutMs: Long = this.writeTimeoutMs
    ) {
        this.activeWriteTimeoutMs = writeTimeoutMs
        log("Connection started: sendBuf=$actualSendBuf recvBuf=$actualRecvBuf tcpNoDelay=${channel.socket().tcpNoDelay} heartbeat=$enableHeartbeat writeTimeout=${activeWriteTimeoutMs}ms")
        lastFrameReceivedAt = System.currentTimeMillis()
        // start() 只是重置看门狗基线；此刻仍然「一帧都没收到」，静默时长继续按 -1 上报。
        hasReceivedFrame = false

        readerJob = scope.launch(Dispatchers.IO) {
            // 8x A53 has no big cores; mark the socket-drain thread urgent so
            // background coroutines on the same pool don't starve frame reads.
            // 平台钩子：Android 用 Process.setThreadPriority(URGENT_DISPLAY)，桌面用 JVM 线程优先级。
            ThreadPriority.elevateCurrent()
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
                    hasReceivedFrame = true

                    if (frame.channel == Channel.CONTROL &&
                        frame.messageType == ControlMsg.HEARTBEAT
                    ) {
                        // Heartbeat ack is a control frame — suspend-enqueue is safe on the reader.
                        enqueueFrame(FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT_ACK, ByteArray(0)))
                        continue
                    }

                    // Video frames: handle inline for low latency.
                    // INPUT（触摸 DOWN/MOVE/UP）与 CONTROL（导航命令）顺序敏感，也必须
                    // 内联按到达顺序处理 —— 逐帧异步派发会打乱触摸注入。只有 DATA 可能
                    // 是重活（应用列表解码），异步派发以免阻塞读线程排空 TCP。
                    val listener = frameListeners[frame.channel]
                    if (listener != null) {
                        if (frame.channel == Channel.VIDEO ||
                            frame.channel == Channel.INPUT ||
                            frame.channel == Channel.CONTROL
                        ) {
                            // A listener that throws must not kill the reader coroutine:
                            // decoders may raise ProtocolDecodeException (not a
                            // ProtocolException) and BufferUnderflowException on
                            // malformed peer payloads, and a SupervisorJob scope has
                            // no handler for them — the socket would sit open until
                            // the watchdog timed out. Fail the connection instead.
                            try {
                                listener.invoke(frame)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                disconnectReason = "frame handler (ch=${frame.channel}, type=0x${Integer.toHexString(frame.messageType.toInt() and 0xFF)}): ${e.javaClass.simpleName}: ${e.message}"
                                log(disconnectReason)
                                if (connected.get()) disconnect()
                                break
                            }
                        } else {
                            scope.launch {
                                try {
                                    listener.invoke(frame)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    disconnectReason = "frame handler (ch=${frame.channel}, type=0x${Integer.toHexString(frame.messageType.toInt() and 0xFF)}): ${e.javaClass.simpleName}: ${e.message}"
                                    log(disconnectReason)
                                    if (connected.get()) disconnect()
                                }
                            }
                        }
                    }
                }
            } catch (e: java.nio.channels.ClosedSelectorException) {
                // Selector closed by disconnect() — normal shutdown
            } catch (e: IOException) {
                disconnectReason = "reader: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            } catch (e: ProtocolException) {
                disconnectReason = "reader: ProtocolException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ProtocolDecodeException, BufferUnderflowException, and anything a
                // future decoder raises are NOT IOException/ProtocolException, so the
                // three catches above never saw them: they escaped into an unhandled
                // coroutine exception (no CoroutineExceptionHandler exists anywhere in
                // this repo) and killed the process on a single malformed frame.
                disconnectReason = "reader: ${e.javaClass.simpleName}: ${e.message}"
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
                for (frame in writeQueue) {
                    pendingSendCount.decrementAndGet()
                    if (!connected.get() || !isActive) break

                    // Encode header — the byte layout lives in FrameCodec only
                    // (single definition shared with writeFrame/writeFrameToChannel).
                    FrameCodec.encodeHeaderInto(headerBuf, frame)

                    // Gathering write: header + payload in single syscall
                    val bufs = if (frame.payload.isNotEmpty()) {
                        arrayOf(ByteBuffer.wrap(headerBuf), ByteBuffer.wrap(frame.payload))
                    } else {
                        arrayOf(ByteBuffer.wrap(headerBuf))
                    }
                    writeBuffersToChannel(bufs)
                    writeCount++
                    if (frame.channel == Channel.VIDEO && writeCount % 60 == 0L) {
                        log("writer: frame #$writeCount ch=${frame.channel} size=${frame.payload.size} stalls=$writeStallCount")
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                // Channel closed by disconnect() — normal shutdown
            } catch (e: CancellationException) {
                // Coroutine cancelled — normal shutdown
            } catch (e: java.nio.channels.ClosedSelectorException) {
                // Selector closed by disconnect() — normal shutdown
            } catch (e: IOException) {
                disconnectReason = "writer: IOException: ${e.message}"
                log(disconnectReason)
                if (connected.get()) disconnect()
            }
        }

        if (enableHeartbeat) {
            keepAlive.start(heartbeatIntervalMs, heartbeatTimeoutMs)
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
        var lastProgressAt = System.currentTimeMillis()
        while (bufs.any { it.hasRemaining() }) {
            val n = channel.write(bufs)
            if (n > 0) {
                lastProgressAt = System.currentTimeMillis()
                continue
            }
            if (!connected.get()) throw IOException("Connection closed during write")
            val now = System.currentTimeMillis()
            if (activeWriteTimeoutMs > 0 && now - lastProgressAt > activeWriteTimeoutMs) {
                val remaining = bufs.sumOf { it.remaining().toLong() }
                throw IOException(
                    "Write timed out: $remaining bytes remaining, send buffer full for ${now - lastProgressAt}ms (timeout=${activeWriteTimeoutMs}ms)"
                )
            }
            writeStallCount++
            if (writeStallCount % 100 == 1L) {
                val remaining = bufs.sumOf { it.remaining().toLong() }
                log("write stall #$writeStallCount: remaining=$remaining")
            }
            delay(1)
        }
    }

    /**
     * Enqueues a frame for writing. Suspending so backpressure from a full queue
     * propagates to the caller (reader / heartbeat / watchdog) instead of dropping.
     */
    private suspend fun enqueueFrame(frame: FrameCodec.Frame) {
        if (!connected.get()) throw IOException("Not connected")
        writeQueue.send(frame)
    }

    fun onFrames(channel: Byte, listener: (FrameCodec.Frame) -> Unit) {
        frameListeners[channel] = listener
    }

    fun onDisconnect(listener: (() -> Unit)?) {
        disconnectListener = listener
    }

    fun clearDisconnectListener() {
        disconnectListener = null
    }

    fun onLog(listener: (String) -> Unit) {
        logListener = listener
    }

    private fun log(msg: String) {
        logListener?.invoke(msg)
    }

    private fun isDroppable(frame: FrameCodec.Frame): Boolean =
        frame.channel == Channel.VIDEO || frame.channel == Channel.AUDIO

    fun sendFrame(frame: FrameCodec.Frame): Boolean {
        if (!connected.get()) throw IOException("Not connected")
        if (isDroppable(frame) && pendingSendCount.get() >= QUEUE_CAPACITY) {
            return false
        }
        pendingSendCount.incrementAndGet()
        scope.launch(sendDispatcher) {
            var enqueued = false
            try {
                sendMutex.withLock {
                    enqueueFrame(frame)
                    enqueued = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // disconnect() closes the writeQueue between the connected check and
                // send(); that surfaces as ClosedSendChannelException (an
                // IllegalStateException, NOT an IOException) — catching only
                // IOException let it escape into an unhandled coroutine exception.
            } finally {
                if (!enqueued) {
                    pendingSendCount.decrementAndGet()
                }
            }
        }
        return true
    }

    suspend fun sendFrameSuspending(frame: FrameCodec.Frame) {
        if (!connected.get()) throw IOException("Not connected")
        sendMutex.withLock {
            enqueueFrame(frame)
        }
    }

    fun sendControl(messageType: Byte, payload: ByteArray = ByteArray(0)): Boolean =
        sendFrame(FrameCodec.Frame(Channel.CONTROL, messageType, payload))

    fun sendVideo(messageType: Byte, payload: ByteArray): Boolean =
        sendFrame(FrameCodec.Frame(Channel.VIDEO, messageType, payload))

    fun sendAudio(messageType: Byte, payload: ByteArray): Boolean =
        sendFrame(FrameCodec.Frame(Channel.AUDIO, messageType, payload))

    fun sendData(messageType: Byte, payload: ByteArray): Boolean =
        sendFrame(FrameCodec.Frame(Channel.DATA, messageType, payload))

    fun sendInput(messageType: Byte, payload: ByteArray): Boolean =
        sendFrame(FrameCodec.Frame(Channel.INPUT, messageType, payload))

    fun disconnect() {
        if (connected.compareAndSet(true, false)) {
            val caller = if (disconnectReason == "unknown") {
                val trace = Thread.currentThread().stackTrace
                val relevant = trace.drop(2).take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}:${it.methodName}:${it.lineNumber}" }
                "external call: $relevant"
            } else disconnectReason
            log("disconnect() reason=$caller")
            readerJob?.cancel()
            keepAlive.stop()
            if (disconnectReason.startsWith("reader: EOF")) {
                // Graceful peer close (DISCONNECT then FIN): frames already queued
                // — e.g. our DISCONNECT_ACK — are still deliverable for a short
                // window. Cancelling the writer and closing the socket instantly
                // (the old behaviour) silently dropped them. Give the writer
                // FLUSH_GRACE_MS to drain, then finish the teardown.
                scope.launch {
                    delay(FLUSH_GRACE_MS)
                    performTeardown()
                }
                return
            }
            performTeardown()
        }
    }

    private fun performTeardown() {
        writerJob?.cancel()
        writeQueue.close()
        while (writeQueue.tryReceive().isSuccess) {
            pendingSendCount.decrementAndGet()
        }
        reader.close()
        try { channel.close() } catch (_: Exception) {}
        try { disconnectListener?.invoke() } catch (_: Exception) {}
    }

    companion object {
        const val QUEUE_CAPACITY = 64
        const val DEFAULT_WRITE_TIMEOUT_MS = 10_000L
        private const val HEARTBEAT_INTERVAL_MS = ConnectionKeepAlive.DEFAULT_HEARTBEAT_INTERVAL_MS
        private const val HEARTBEAT_TIMEOUT_MS = ConnectionKeepAlive.DEFAULT_HEARTBEAT_TIMEOUT_MS
        const val CONNECT_TIMEOUT_MS = ConnectionFactory.CONNECT_TIMEOUT_MS

        /** Grace period for the writer to drain queued frames on a graceful peer close. */
        private const val FLUSH_GRACE_MS = 500L

        /**
         * TCP send/receive buffer request (512KB). Raised above the OS default
         * because 1080p keyframes burst larger than the default window.
         * Public because vd-server's socket setup uses the same value — this
         * constant is the single source for both ends of the video path.
         *
         * issue #70：显式设置 SO_SNDBUF 会关掉内核自动调大，所以这是个**硬上限**。
         * 原来是 256KB，2.5h 导航日志里关键帧突发把缓冲填满后出现连续 5s 零进展
         * （`Write timed out`），整条会话被判死重连；加到 512KB 多留一档突发余量。
         */
        const val SOCKET_BUF_BYTES = 524288

        suspend fun connect(host: String, port: Int, scope: CoroutineScope): Connection =
            ConnectionFactory.connect(host, port, scope)

        /**
         * @param timeoutMs deadline for the TCP handshake. A black-holed SYN used
         *   to spin `finishConnect()` forever (only the desktop's wrapper had a
         *   timeout); now the channel is closed and the connect fails.
         */
        suspend fun connect(
            host: String,
            port: Int,
            scope: CoroutineScope,
            timeoutMs: Long = CONNECT_TIMEOUT_MS
        ): Connection =
            ConnectionFactory.connect(host, port, scope, timeoutMs)

        suspend fun accept(
            port: Int,
            scope: CoroutineScope
        ): Connection =
            ConnectionFactory.accept(port, scope)
    }
}
