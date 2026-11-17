package com.dilinkauto.protocol

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Loopback socket pair shared by [ConnectionTest] and [ConnectionStressTest].
 *
 * Both suites needed the identical 12-line fixture (docs/audit-srp-dry.md DRY-9),
 * so a change to how the pair is set up — or a fix to the accept-then-close
 * ordering — had to be applied twice.
 */
internal object TestSockets {

    /**
     * @return (client, accepted) connected over loopback. The listening socket is
     *   closed before returning; the two ends remain usable.
     */
    fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port

        val client = SocketChannel.open()
        client.connect(InetSocketAddress("127.0.0.1", port))
        val accepted = server.accept()
        server.close()

        return Pair(client, accepted)
    }
}