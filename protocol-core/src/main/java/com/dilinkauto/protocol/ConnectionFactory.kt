package com.dilinkauto.protocol

import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Factory for creating [Connection] instances via non-blocking TCP connect or accept.
 */
object ConnectionFactory {
    const val CONNECT_TIMEOUT_MS = 10000L

    suspend fun connect(host: String, port: Int, scope: CoroutineScope): Connection =
        connect(host, port, scope, CONNECT_TIMEOUT_MS)

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
        withContext(Dispatchers.IO) {
            val channel = SocketChannel.open()
            try {
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(host, port))
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) {
                        throw IOException("Connect to $host:$port timed out after ${timeoutMs}ms")
                    }
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
