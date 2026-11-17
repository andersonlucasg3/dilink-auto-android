package com.dilinkauto.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默时长基线（issue #200 评审）。
 *
 * `lastFrameReceivedAt` 的初始值是**构造时刻**，`start()` 还会重置成启动时刻。
 * 若拿它们当基线，「握手/VD 引擎启动慢于 6s 阈值」就会被手机端读成死链并主动重建
 * （实测 3.7~4.9s，比现状更糟）。所以契约是：**没收到过帧就返回 -1**，调用方
 * （`NetworkRebuildPolicy`）把 -1 当「无法判断」不重建。
 */
class ConnectionSilenceBaselineTest {

    @Test
    fun `构造后未收到任何帧时返回 -1`() = runBlocking {
        val (sock, peer) = TestSockets.createConnectedSockets()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val conn = Connection(sock, scope)
        try {
            assertEquals("构造时刻不是「收到帧」的证据", -1L, conn.msSinceLastFrameReceived())
        } finally {
            conn.disconnect()
            peer.close()
            scope.cancel()
        }
    }

    @Test
    fun `start 只重置看门狗基线，仍返回 -1`() = runBlocking {
        val (sock, peer) = TestSockets.createConnectedSockets()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val conn = Connection(sock, scope)
        try {
            conn.start(enableHeartbeat = false)
            Thread.sleep(120) // 让「启动时刻」明显早于调用时刻，暴露错误的基线
            assertEquals("start() 之后仍一帧未收，必须继续返回 -1", -1L, conn.msSinceLastFrameReceived())
        } finally {
            conn.disconnect()
            peer.close()
            scope.cancel()
        }
    }

    @Test
    fun `真正读到一帧后才开始计时`() = runBlocking {
        val (sock, peer) = TestSockets.createConnectedSockets()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val conn = Connection(sock, scope)
        try {
            conn.start(enableHeartbeat = false)
            FrameCodec.writeFrame(
                peer.socket().getOutputStream(),
                FrameCodec.Frame(Channel.CONTROL, ControlMsg.HEARTBEAT, ByteArray(0)),
            )
            val deadline = System.currentTimeMillis() + 3_000
            var silence = -1L
            while (System.currentTimeMillis() < deadline && silence < 0L) {
                Thread.sleep(20)
                silence = conn.msSinceLastFrameReceived()
            }
            assertTrue("收到帧后必须给出非负的静默时长，实际=$silence", silence >= 0L)
            assertTrue("刚收到的帧不应被算成很久以前，实际=${silence}ms", silence < 1_000L)
        } finally {
            conn.disconnect()
            peer.close()
            scope.cancel()
        }
    }
}
