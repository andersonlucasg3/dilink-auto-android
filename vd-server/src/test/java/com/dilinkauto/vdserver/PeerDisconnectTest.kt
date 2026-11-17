package com.dilinkauto.vdserver

import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isPeerDisconnect] 契约测试（2026-10-17 S19 真机实证）。
 *
 * 会话被对端终结（Eject / 拔线 / 引擎被重建）时视频写路径抛 Broken pipe 等 IO 异常，
 * 它们是正常终止形态——runPipeline 的兜底 catch 对它们只打一行消息、不打完整堆栈
 * （修复前 20 轮循环 9 次 Broken pipe 堆栈把 vd-server.log 的 Exception 断言打爆）。
 * 契约：只有 IO 异常且消息匹配断连三类形态才为 true；其余（含非 IO 异常、null 消息）
 * 一律 false，照旧打堆栈。
 */
class PeerDisconnectTest {

    @Test
    fun `broken pipe is a peer disconnect`() {
        assertTrue(isPeerDisconnect(IOException("Broken pipe")))
    }

    @Test
    fun `connection reset is a peer disconnect`() {
        assertTrue(isPeerDisconnect(IOException("Connection reset")))
        assertTrue(isPeerDisconnect(SocketException("Connection reset by peer")))
    }

    @Test
    fun `socket closed is a peer disconnect`() {
        assertTrue(isPeerDisconnect(IOException("Socket closed")))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(isPeerDisconnect(IOException("broken pipe")))
        assertTrue(isPeerDisconnect(IOException("BROKEN PIPE")))
        assertTrue(isPeerDisconnect(IOException("connection reset")))
    }

    @Test
    fun `other io exceptions are not peer disconnects`() {
        assertFalse(isPeerDisconnect(IOException("No space left on device")))
        assertFalse(isPeerDisconnect(IOException("Permission denied")))
        assertFalse(isPeerDisconnect(IOException("connect timed out")))
    }

    @Test
    fun `non io exceptions are not peer disconnects even with matching message`() {
        assertFalse(isPeerDisconnect(RuntimeException("Broken pipe")))
        assertFalse(isPeerDisconnect(IllegalStateException("Socket closed")))
    }

    @Test
    fun `null or empty messages are not peer disconnects`() {
        assertFalse(isPeerDisconnect(IOException()))
        assertFalse(isPeerDisconnect(IOException("")))
    }
}
