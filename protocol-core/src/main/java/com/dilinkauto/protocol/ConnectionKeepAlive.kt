package com.dilinkauto.protocol

import kotlinx.coroutines.*
import java.util.concurrent.CancellationException

/**
 * Manages periodic heartbeat transmission and watchdog timeout monitoring for a [Connection].
 */
class ConnectionKeepAlive(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val getLastFrameReceivedAt: () -> Long,
    private val sendHeartbeat: suspend () -> Unit,
    private val onDisconnect: (reason: String) -> Unit,
    private val log: (String) -> Unit
) {
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    fun start(
        heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS
    ) {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected()) {
                delay(heartbeatIntervalMs)
                if (!isConnected()) break
                try {
                    sendHeartbeat()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason = "heartbeat: ${e.javaClass.simpleName}: ${e.message}"
                    log(reason)
                    onDisconnect(reason)
                    break
                }
            }
        }

        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected()) {
                delay(heartbeatIntervalMs)
                val elapsed = System.currentTimeMillis() - getLastFrameReceivedAt()
                if (elapsed > heartbeatTimeoutMs) {
                    val reason = "watchdog: no frame received for ${elapsed}ms (timeout=${heartbeatTimeoutMs}ms)"
                    log(reason)
                    onDisconnect(reason)
                    break
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        heartbeatJob = null
        watchdogJob = null
    }

    companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 3000L
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 10000L
    }
}
