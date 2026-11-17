package com.dilinkauto.server.adb

import com.dilinkauto.protocol.adb.TcpAdbConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins [RemoteAdbController]'s transport-failure contract (2026-10-17 S05
 * live root-cause fix).
 *
 * `TcpAdbConnection.shell()` / `shellBackground()` swallow transport errors and
 * report them as `-1` instead of throwing. The controller must treat that as
 * "connection is dead" and clear `isConnected`, otherwise a half-open socket
 * (phone-side WiFi flap) is reused forever: the car logs
 * `Dev mode: TCP ADB still connected`, the deploy dies in ~150 ms
 * (`VD server failed to start`), and the reconnect loop never recovers until
 * the app process restarts.
 */
class RemoteAdbControllerTransportFailureTest {

    /**
     * Scripted fake. Constructing [TcpAdbConnection] never opens a socket
     * (only `connect()` does), so the superclass call is inert here.
     */
    private class FakeConnection(
        var shellRc: Int = 0,
        var backgroundId: Int = 1,
        var noWaitOk: Boolean = true,
    ) : TcpAdbConnection("127.0.0.1", 0, File(".")) {
        override fun connect(): Boolean = true
        override fun shell(command: String, outputSink: StringBuilder?): Int = shellRc
        override fun shellBackground(command: String): Int = backgroundId
        override fun shellNoWait(command: String): Boolean = noWaitOk
        override fun close() {}
    }

    private fun controller(fake: FakeConnection) = RemoteAdbController(
        phoneHost = "127.0.0.1",
        virtualDisplayId = -1,
        keyDir = File("."),
        connectionFactory = { _, _, _ -> fake },
    )

    @Test
    fun `connect marks the controller connected`() {
        val c = controller(FakeConnection())
        assertTrue(c.connect())
        assertTrue(c.isConnected)
    }

    @Test
    fun `shell transport failure returns false and marks the controller disconnected`() {
        val fake = FakeConnection(shellRc = -1)
        val c = controller(fake)
        assertTrue(c.connect())

        assertFalse("传输层失败（-1）必须返回 false", c.shell("echo hi"))
        assertFalse(
            "传输层失败后必须标记断开——否则半开 socket 被永久复用（S05 实测）",
            c.isConnected
        )
    }

    @Test
    fun `shell success keeps the controller connected`() {
        val c = controller(FakeConnection(shellRc = 0))
        assertTrue(c.connect())

        assertTrue(c.shell("echo hi"))
        assertTrue("命令成功不得误判连接已死", c.isConnected)
    }

    @Test
    fun `shellBackground failure returns minus one and marks the controller disconnected`() {
        val fake = FakeConnection(backgroundId = -1)
        val c = controller(fake)
        assertTrue(c.connect())

        assertTrue(c.shellBackground("app_process ...") < 0)
        assertFalse("launch 失败 = 传输层错误，必须标记断开", c.isConnected)
    }

    @Test
    fun `shellNoWait failure marks the controller disconnected`() {
        val fake = FakeConnection(noWaitOk = false)
        val c = controller(fake)
        assertTrue(c.connect())

        assertFalse(c.shellNoWait("settings put global x 1"))
        assertFalse("fire-and-forget 失败同样说明通道已死", c.isConnected)
    }

    @Test
    fun `disconnect clears the connection state`() {
        val c = controller(FakeConnection())
        assertTrue(c.connect())
        c.disconnect()
        assertFalse(c.isConnected)
        // 断开后再发命令：没有连接对象 → 直接失败且不抛
        assertFalse(c.shell("echo hi"))
    }
}
