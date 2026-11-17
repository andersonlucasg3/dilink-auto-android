package com.dilinkauto.server.service

import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Encodes and sends touch events from the car to the phone's VD server on the
 * input connection (port 9639).
 *
 * A dedicated single-thread executor keeps touch encoding off the UI thread
 * and serializes sends so the input channel's frame ordering stays stable.
 * Drop/send counters gate log spam via [shouldLog]: the first few events
 * ([SEND_LOG_FIRST] sends / [DROP_LOG_FIRST] drops) and every [LOG_EVERY]-th
 * are logged, which keeps the phone log readable during a touch storm.
 *
 * Extracted from [CarConnectionService].
 */
internal class CarTouchSender(
    private val inputConnectionProvider: () -> Connection?,
    private val stateProvider: () -> CarConnectionService.State,
    private val log: (String, String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touch-sender").apply { isDaemon = true }
    }
    private val dropCount = AtomicLong(0L)
    private val sendCount = AtomicLong(0L)

    /** issue #64：多指 MOVE 批次单独计数，用于和手机端 `[TouchStats]` 对账。 */
    private val multiBatchCount = AtomicLong(0L)

    internal fun getDropCount(): Long = dropCount.get()
    internal fun getSendCount(): Long = sendCount.get()
    internal fun getMultiBatchCount(): Long = multiBatchCount.get()

    /**
     * Log-spam gate (audit R3-DRY-22): log the first [firstN] events, then
     * every [LOG_EVERY]-th. Single definition — the predicate used to be
     * written out four times, in two variants that could drift apart.
     */
    private fun shouldLog(count: Long, firstN: Long): Boolean =
        count <= firstN || count % LOG_EVERY == 0L

    fun sendTouchEvent(event: TouchEvent) {
        val conn = checkConn("Touch") ?: return
        val payload = event.encode()
        executor.execute {
            try {
                conn.sendInput(event.action, payload)
                val count = sendCount.incrementAndGet()
                if (shouldLog(count, SEND_LOG_FIRST)) {
                    log("Touch #$count action=${event.action} ptr=${event.pointerId} x=${"%.2f".format(event.x)} y=${"%.2f".format(event.y)}", "I")
                }
            } catch (e: Exception) { log("Touch send failed: ${e.message}", "W") }
        }
    }

    fun sendTouchBatch(pointers: List<TouchEvent>) {
        val conn = checkConn("Touch batch") ?: return
        val payload = TouchMoveBatch(pointers).encode()
        executor.execute {
            try {
                conn.sendInput(InputMsg.TOUCH_MOVE_BATCH, payload)
                val count = sendCount.incrementAndGet()
                // issue #64：多指批次按「前 N 条 + 每 M 条」单独落日志。
                // 手机端在 `TouchInjector` 里也有对应的 `[Touch] MOVE n=...`，
                // 两边对不上就能判断是链路丢帧还是注入侧丢事件。
                if (pointers.size >= 2) {
                    val multiCount = multiBatchCount.incrementAndGet()
                    if (multiCount <= MULTI_LOG_FIRST || multiCount % MULTI_LOG_EVERY == 0L) {
                        log(
                            "Touch multi #$multiCount (${pointers.size} pointers ids=${pointers.map { it.pointerId }})",
                            "I"
                        )
                    }
                } else if (shouldLog(count, SEND_LOG_FIRST)) {
                    log("Touch batch #$count (${pointers.size} pointers)", "I")
                }
            } catch (e: Exception) { log("Touch batch send failed: ${e.message}", "W") }
        }
    }

    /**
     * Shared null-conn + not-connected check. Returns the connection if usable,
     * or null after bumping dropCount and logging the reason. Both public send
     * methods route through this so the gating predicate lives in one place.
     */
    private fun checkConn(label: String): Connection? {
        val conn = inputConnectionProvider()
        if (conn == null) {
            val drops = dropCount.incrementAndGet()
            if (shouldLog(drops, DROP_LOG_FIRST)) {
                log("$label DROP #$drops: inputConnection=null state=${stateProvider()}", "I")
            }
            return null
        }
        if (!conn.isConnected) {
            val drops = dropCount.incrementAndGet()
            if (shouldLog(drops, DROP_LOG_FIRST)) {
                log("$label DROP #$drops: inputConnection not connected", "I")
            }
            return null
        }
        return conn
    }

    /** Reset counters on disconnect so the next session's log starts fresh. */
    fun resetCounters() {
        sendCount.set(0L)
        dropCount.set(0L)
        multiBatchCount.set(0L)
    }

    fun shutdown() { executor.shutdownNow() }

    private companion object {
        /** First N successful sends are always logged (session start is interesting). */
        const val SEND_LOG_FIRST = 5L

        /** First N drops are always logged — fewer than sends, drops repeat verbatim. */
        const val DROP_LOG_FIRST = 3L

        /** After the first few, log every Nth event. */
        const val LOG_EVERY = 100L

        /** issue #64：多指批次前 10 条全记，之后每 50 条记 1 条。 */
        const val MULTI_LOG_FIRST = 10L
        const val MULTI_LOG_EVERY = 50L
    }
}
