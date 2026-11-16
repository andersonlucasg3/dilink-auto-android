package com.dilinkauto.server.service

import android.util.Log
import com.dilinkauto.protocol.AsyncLogQueue
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.DataMsg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Car → phone log relay.
 *
 * Callers do a non-blocking [send] (logcat + queue); a dedicated coroutine on
 * [Dispatchers.IO] formats, encodes, and ships each line as a [DataMsg.CAR_LOG]
 * frame on the control connection. When the control connection is down, lines
 * are buffered in a bounded [logBuffer] (10k cap) and flushed on reconnect, so
 * diagnostic context around disconnects/crashes is preserved.
 *
 * Extracted from [CarConnectionService] to isolate the log machinery from the
 * connection state machine.
 */
internal class CarLogWriter(
    private val tag: String,
    /** 单调时钟（毫秒），供积压延迟统计使用；单测可注入假时钟。 */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val controlConnectionProvider: () -> Connection?,
) {
    private data class LogEntry(val msg: String, val level: String, val enqueuedMs: Long)

    /** Off-thread log queue: callers do a non-blocking offer; bounded at 1024. */
    private val logQueue = AsyncLogQueue<LogEntry>(1024)
    private val writerJob = Job()
    private val scope = CoroutineScope(writerJob + Dispatchers.IO)

    // Buffer used when the control connection is down. ConcurrentLinkedQueue for
    // multi-thread puts (touch-sender, video decoder, USB receiver all log).
    private val buffer = ConcurrentLinkedQueue<String>()
    // O(1) counter for the cap check — ConcurrentLinkedQueue.size() is O(n) and
    // was called per log line on the disconnected path.
    private val bufferCount = AtomicInteger(0)

    private val tsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** issue #206：回传积压的限流上报（每 5s 最多一行）。 */
    private val backlogReporter = CarLogBacklogReporter(nowMs)

    private companion object {
        /** 离线缓冲上限（行）：车机内存有限，断线期间只留最近的上下文。 */
        const val MAX_BUFFER_LINES = 10000
    }

    @Volatile private var enabled: Boolean = false
    @Volatile private var started: Boolean = false

    /** Release builds default to off; the phone toggles via LOG_TOGGLE. */
    fun setEnabled(value: Boolean) { enabled = value }

    /**
     * Start of a new session: drop anything buffered from the previous one.
     *
     * The offline buffer is only meaningful for the session that produced it —
     * carrying 10k lines of a previous session's disconnect context into the
     * next one wastes memory on a 4GB device and flushes stale context on
     * reconnect (audit S-L4). Called by [CarConnectionService.startConnection].
     *
     * [keepMarkers] 命中的行跨会话保留：这些行恰好产生在断线瞬间 —— 此时控制链路已断，
     * 只能进 buffer，而下个会话的 [beginSession] 会把它清掉，于是最需要看见的结论永远
     * 到不了手机。
     *  - `BydVehicleProbe.TAG`：探针的 `skipped` / `crashed`（issue #188）；
     *  - `[Connect]`：用户断开后哪些自动入口被挡下来、下一次连接由谁发起（issue #201）。
     *
     * Deliberately does NOT clear [logQueue]: lines still queued there belong
     * to the teardown that just finished and are worth shipping.
     */
    fun beginSession(vararg keepMarkers: String) {
        val kept = ArrayList<String>()
        while (true) {
            val line = buffer.poll() ?: break
            bufferCount.decrementAndGet()
            if (keepMarkers.any { line.contains(it) }) kept.add(line)
        }
        for (line in kept) {
            // 重放路径同样受 [MAX_BUFFER_LINES] 约束，避免多次「连不上→重连」把保留行堆上去。
            if (bufferCount.get() >= MAX_BUFFER_LINES) break
            buffer.add(line)
            bufferCount.incrementAndGet()
        }
    }

    /** Idempotent — safe to call from [CarConnectionService.onCreate]. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                logQueue.consume { entry ->
                    try {
                        // issue #206：先量「产生 → 写出」的延迟，再真正写出。
                        reportBacklogIfAny(entry)
                        val ts = LocalTime.now().format(tsFormatter)
                        val line = com.dilinkauto.protocol.LogLine.bracketed(ts, entry.level, entry.msg)
                        val conn = controlConnectionProvider()
                        if (conn != null && conn.isConnected) {
                            // Flush any buffered messages first.
                            while (true) {
                                val buffered = buffer.poll() ?: break
                                bufferCount.decrementAndGet()
                                try { conn.sendData(DataMsg.CAR_LOG, buffered.toByteArray(Charsets.UTF_8)) }
                                catch (_: Exception) { break }
                            }
                            try { conn.sendData(DataMsg.CAR_LOG, line.toByteArray(Charsets.UTF_8)) }
                            catch (_: Exception) {}
                        } else {
                            // Buffer for later — cap at [MAX_BUFFER_LINES].
                            if (bufferCount.get() < MAX_BUFFER_LINES) {
                                buffer.add(line)
                                bufferCount.incrementAndGet()
                            }
                        }
                    } catch (_: Throwable) {
                        // Never let the writer die on a single bad entry.
                    }
                }
            } catch (_: CancellationException) {
                // shutdown
            }
        }
    }

    /** Log to logcat (honoring [enabled]) and enqueue for phone relay. */
    fun send(msg: String, level: String = "I") {
        if (!enabled) return
        write(msg, level)
    }

    /**
     * 绕过 [enabled] 的强制上报（issue #188 探针这类一次性诊断用）。
     *
     * 不能靠临时 `setEnabled(true)` 实现：那会和手机下发的 `LOG_TOGGLE` 抢同一个开关，
     * 结束时还原还会把用户刚打开的日志关掉（#72 回归）。
     */
    fun sendUnconditional(msg: String, level: String = "I") = write(msg, level)

    private fun write(msg: String, level: String) {
        when (level) {
            "D" -> Log.d(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
            else -> Log.i(tag, msg)
        }
        logQueue.offer(LogEntry(msg, level, nowMs()))
    }

    /**
     * 回传积压的可见化（issue #206）。
     *
     * 延迟用**车机本机的单调时钟**度量（入队时刻 → 出队时刻），不用墙上时钟：
     * 手机与车机存在约 0.8s 的时钟偏差（实车 `client.log:108` 外层 `16:35:05.418`
     * 比内嵌 `16:35:06.133` 早 0.7s），跨端直接相减会把偏差算成延迟。
     *
     * 告警行**绕开 [logQueue] 直发控制链路**：它本身就是「队列里有积压」的证据，
     * 再走队列会把自己排到积压后面，等它送达时早已不是当时的现场。
     * 不因此丢弃任何行 —— teardown / cleanup 关键行照旧全量写出。
     */
    private fun reportBacklogIfAny(entry: LogEntry) {
        val line = backlogReporter.onDrained(
            delayMs = nowMs() - entry.enqueuedMs,
            backlog = logQueue.pendingCount + bufferCount.get(),
        ) ?: return
        Log.w(tag, line)
        val conn = controlConnectionProvider() ?: return
        if (!conn.isConnected) return
        try {
            val ts = LocalTime.now().format(tsFormatter)
            conn.sendData(
                DataMsg.CAR_LOG,
                com.dilinkauto.protocol.LogLine.bracketed(ts, "W", line).toByteArray(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            // 诊断行发不出去不影响正常日志路径
        }
    }

    /**
     * Cancel the writer coroutine and close the queue. Called from
     * [CarConnectionService.shutdown].
     *
     * The queue must be closed, not just the job cancelled: a consumer parked
     * in `take()` on cancel never drains, and an unclosed queue keeps every
     * entry it holds alive for the rest of the process (audit S-L4).
     */
    fun shutdown() {
        writerJob.cancel()
        logQueue.close()
        // Drop anything still buffered — the writer is gone, so flushing is
        // impossible and the entries would otherwise pin memory forever.
        while (true) { if (buffer.poll() == null) break }
        bufferCount.set(0)
    }
}
