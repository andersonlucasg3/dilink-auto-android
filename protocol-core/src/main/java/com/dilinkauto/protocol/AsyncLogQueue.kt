package com.dilinkauto.protocol

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared "non-blocking enqueue → single consumer" skeleton for the log sinks
 * (phone `FileLog`, car `CarLogWriter`; audit R3-DRY-05).
 *
 * Callers enqueue from any thread without blocking; exactly one consumer
 * drains in FIFO order via [consume]. The *consumer loop* stays caller-owned
 * because the two call sites deliberately differ:
 *  - `FileLog`: a dedicated daemon thread (object without a lifecycle scope)
 *  - `CarLogWriter`: a coroutine on Dispatchers.IO
 * while the queue mechanics — thread-safe offer, bounded overflow policy,
 * ordered drain — live here in one place.
 *
 * @param capacity max queued items. When full, the newest item is dropped
 *        (trySend semantics — matches CarLogWriter's previous `Channel(1024)`).
 *        Defaults to unbounded, matching FileLog's previous
 *        `ConcurrentLinkedQueue`.
 */
class AsyncLogQueue<T : Any>(capacity: Int = Channel.UNLIMITED) {

    private val channel = Channel<T>(capacity)

    /**
     * 队列里还没被 [consume] 取走的条数（issue #206 的 `CarLog backlog=` 用它）。
     *
     * `Channel` 不暴露深度，这里只在「成功入队 / 被取走」两处维护一个 O(1) 计数器：
     * 队满时 `trySend` 会丢最新项，失败不入账，保证计数只反映真正留在队列里的条目。
     */
    private val pending = AtomicInteger(0)

    val pendingCount: Int get() = pending.get()

    /** Non-blocking enqueue. Drops the item when the queue is at [capacity]. */
    fun offer(item: T) {
        if (channel.trySend(item).isSuccess) pending.incrementAndGet()
    }

    /**
     * Drain the queue forever, handing each item to [sink] in FIFO order.
     * Suspends while empty; returns when the channel is [close]d or the
     * calling coroutine is cancelled.
     */
    suspend fun consume(sink: (T) -> Unit) {
        for (item in channel) {
            pending.decrementAndGet()
            sink(item)
        }
    }

    /** Best-effort discard of everything currently queued (used by log rotate). */
    fun clear() {
        while (channel.tryReceive().isSuccess) { pending.decrementAndGet() }
    }

    /** Close the channel — a running [consume] loop terminates. */
    fun close() {
        channel.close()
    }
}
