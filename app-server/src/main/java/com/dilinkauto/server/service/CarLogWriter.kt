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
    private val controlConnectionProvider: () -> Connection?
) {
    private data class LogEntry(val msg: String, val level: String)

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
     * Deliberately does NOT clear [logQueue]: lines still queued there belong
     * to the teardown that just finished and are worth shipping.
     */
    fun beginSession() {
        while (true) { if (buffer.poll() == null) break }
        bufferCount.set(0)
    }

    /** Idempotent — safe to call from [CarConnectionService.onCreate]. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                logQueue.consume { entry ->
                    try {
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
                            // Buffer for later — cap at 10000 lines.
                            if (bufferCount.get() < 10000) {
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
        logQueue.offer(LogEntry(msg, level))
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
