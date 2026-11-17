package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PortAcceptorTest {

    @Test
    fun openAndBindOpensNonBlockingChannel() {
        val server = PortAcceptor.openAndBind(0, "127.0.0.1")
        try {
            assertTrue(server.isOpen)
            assertFalse(server.isBlocking)
            assertTrue(server.socket().reuseAddress)
            val port = (server.localAddress as InetSocketAddress).port
            assertTrue(port > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun acceptCarChannelAcceptsAllowedPeer() {
        val server = PortAcceptor.openAndBind(0, "127.0.0.1")
        val port = (server.localAddress as InetSocketAddress).port
        val done = CountDownLatch(1)
        var clientChannel: SocketChannel? = null
        var acceptedChannel: SocketChannel? = null

        val t = daemonThread("TestAccept") {
            acceptedChannel = PortAcceptor.acceptCarChannel(
                server = server,
                timeoutMs = 3000,
                socketBufBytes = 32768,
                isAllowedPeer = { true },
                isRunning = { true }
            )
            done.countDown()
        }

        try {
            clientChannel = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNotNull(acceptedChannel)
            assertFalse(acceptedChannel!!.isBlocking)
            assertEquals(32768, acceptedChannel!!.socket().sendBufferSize)
            assertEquals(32768, acceptedChannel!!.socket().receiveBufferSize)
            assertTrue(acceptedChannel!!.socket().tcpNoDelay)
        } finally {
            runCatching { clientChannel?.close() }
            runCatching { acceptedChannel?.close() }
            runCatching { server.close() }
            t.interrupt()
        }
    }

    @Test
    fun acceptCarChannelRejectsDisallowedPeer() {
        val server = PortAcceptor.openAndBind(0, "127.0.0.1")
        val port = (server.localAddress as InetSocketAddress).port
        val done = CountDownLatch(1)
        val refused = AtomicBoolean(false)
        var clientChannel: SocketChannel? = null
        var acceptedChannel: SocketChannel? = null

        val t = daemonThread("TestAcceptReject") {
            acceptedChannel = PortAcceptor.acceptCarChannel(
                server = server,
                timeoutMs = 500,
                socketBufBytes = 32768,
                isAllowedPeer = { false },
                isRunning = { true },
                onRefusedPeer = { refused.set(true) }
            )
            done.countDown()
        }

        try {
            clientChannel = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull("Disallowed peer should not be accepted", acceptedChannel)
            assertTrue("onRefusedPeer should be called", refused.get())
        } finally {
            runCatching { clientChannel?.close() }
            runCatching { server.close() }
            t.interrupt()
        }
    }
}
