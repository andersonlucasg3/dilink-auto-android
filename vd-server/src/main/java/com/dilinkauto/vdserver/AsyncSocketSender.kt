package com.dilinkauto.vdserver

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 通用异步 Socket 发送器：有界发送队列 + 单独立写线程（DRY）。
 *
 * 收敛 [VideoFrameSender] 与 [SocketAudioSink] 中重复的线程锁/条件变量/优雅停机循环模板：
 * - 唯一写线程保序写出（入队顺序 = 调用顺序）；
 * - 线程安全的条件变量等待与非阻塞入队；
 * - 优雅停机与关闭超时保护；
 * - 遇到写入异常时标记失败、清空队列并安全退出写循环。
 *
 * @param T 队列承载的数据项类型
 * @param capacity 队列容量上限（必须 > 0）
 * @param threadName 写线程名称
 * @param writeItem 真正的写出动作（阻塞 I/O）
 * @param closeChannel 关闭底层的通道，用于解除阻塞在读写上的自旋
 * @param onWriteFailed 发生写入异常时的回调
 * @param onItemSent 成功写出一项后的回调（携带项与耗时纳秒）
 * @param closeJoinTimeoutMs close() 等待写线程退出的超时上限（毫秒）
 * @param pollMillis 队列为空时条件变量等待的轮询粒度（毫秒）
 */
internal class AsyncSocketSender<T>(
    val capacity: Int,
    private val threadName: String,
    private val writeItem: (T) -> Unit,
    private val closeChannel: () -> Unit = {},
    private val onWriteFailed: (Exception) -> Unit = {},
    private val onItemSent: ((sender: AsyncSocketSender<T>, item: T, writeNanos: Long) -> Unit)? = null,
    private val closeJoinTimeoutMs: Long = CLOSE_JOIN_TIMEOUT_MS,
    private val pollMillis: Long = POLL_MILLIS
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<T>(capacity)

    @Volatile
    var running = true
        private set

    /** 写线程因异常退出后置位。 */
    @Volatile
    var failed = false
        private set

    @Volatile
    private var writer: Thread? = null

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    /** 启动写线程（幂等）。 */
    fun start() {
        if (writer != null) return
        writer = daemonThread(threadName, start = true) {
            writerLoop()
        }
    }

    /**
     * 当前队列深度。
     */
    val queued: Int
        get() = lock.withLock { queue.size }

    /**
     * 在内部锁保护下执行代码块（用于同步统计量或读取状态）。
     */
    fun <R> withLock(block: () -> R): R = lock.withLock(block)

    /**
     * 将数据项入队。
     *
     * @param item 要入队的数据项
     * @param evictIfFull 当队列满时执行的驱逐策略，传入当前队列，返回 true 表示成功驱逐并允许新项入队；返回 false 表示放弃入队新项
     * @return true 表示入队成功；false 表示被丢弃或队列已不可用
     */
    fun enqueue(item: T, evictIfFull: (ArrayDeque<T>) -> Boolean): Boolean = lock.withLock {
        if (!running || failed) return false
        if (queue.size >= capacity) {
            val evicted = evictIfFull(queue)
            if (!evicted) return false
        }
        queue.addLast(item)
        notEmpty.signal()
        true
    }

    override fun close() {
        running = false
        runCatching { closeChannel() }
        lock.withLock { notEmpty.signalAll() }
        val t = writer
        writer = null
        if (t != null) {
            runCatching { t.interrupt() }
            runCatching { t.join(closeJoinTimeoutMs) }
        }
    }

    private fun writerLoop() {
        try {
            while (true) {
                val item = lock.withLock {
                    while (running && queue.isEmpty()) {
                        notEmpty.await(pollMillis, TimeUnit.MILLISECONDS)
                    }
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break

                val startedAt = System.nanoTime()
                try {
                    writeItem(item)
                } catch (e: Exception) {
                    failed = true
                    lock.withLock { queue.clear() }
                    onWriteFailed(e)
                    return
                }
                val took = System.nanoTime() - startedAt
                onItemSent?.invoke(this, item, took)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** 写线程空闲时等待下一项的轮询粒度（毫秒）。 */
        const val POLL_MILLIS = 100L

        /** close() 等写线程退出的上限（毫秒）。 */
        const val CLOSE_JOIN_TIMEOUT_MS = 1500L
    }
}
