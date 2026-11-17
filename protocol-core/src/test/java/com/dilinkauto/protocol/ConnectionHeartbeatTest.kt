package com.dilinkauto.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connection 心跳/看门狗的活性契约（此前所有测试都用 enableHeartbeat=false，整条活性契约零覆盖）。
 *
 * start() 新增可注入的心跳间隔/超时参数（默认仍是 3s/10s，行为不变），故这里用极小值在毫秒级验证，
 * 无需等待真实的 3s/10s。协程跑在 Dispatchers.IO 上，用 runBlocking + latch（真实时间），不用虚拟时钟。
 */
class ConnectionHeartbeatTest {

    @Test
    fun `a sent heartbeat is auto acked by the peer`() = runBlocking {
        val (clientSock, serverSock) = TestSockets.createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = Connection(clientSock, clientScope)
        val server = Connection(serverSock, serverScope)

        val ack = CountDownLatch(1)
        // 客户端发出 HEARTBEAT；服务端 reader 自动回 HEARTBEAT_ACK；客户端 CONTROL 监听收到它
        client.onFrames(Channel.CONTROL) { frame ->
            if (frame.messageType == ControlMsg.HEARTBEAT_ACK) ack.countDown()
        }
        try {
            client.start(enableHeartbeat = true, heartbeatIntervalMs = 30L)
            server.start(enableHeartbeat = false) // 只跑 reader 以自动 ACK
            assertTrue("未在超时内收到 HEARTBEAT_ACK", ack.await(5, TimeUnit.SECONDS))
        } finally {
            client.disconnect(); server.disconnect()
            clientScope.cancel(); serverScope.cancel()
        }
    }

    @Test
    fun `watchdog disconnects a peer that goes silent`() = runBlocking {
        val (clientSock, serverSock) = TestSockets.createConnectedSockets()
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = Connection(serverSock, serverScope)

        val gone = CountDownLatch(1)
        server.onDisconnect { gone.countDown() }
        try {
            // clientSock 保持打开但静默（保持引用，不读不写不关）→ 无帧到达 → 看门狗超时断开
            assertTrue(server.isConnected)
            server.start(enableHeartbeat = true, heartbeatIntervalMs = 30L, heartbeatTimeoutMs = 80L)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && server.isConnected) {
                Thread.sleep(20)
            }
            assertFalse("看门狗应在超时后断开静默对端", server.isConnected)
            assertTrue("onDisconnect 应触发", gone.await(2, TimeUnit.SECONDS))
        } finally {
            server.disconnect()
            serverScope.cancel()
            clientSock.close()
            serverSock.close()
        }
    }

    @Test
    fun `a live peer that keeps sending is never dropped by the watchdog`() = runBlocking {
        // 反向：对端持续发帧刷新 lastFrameReceivedAt，看门狗不应误杀。
        val (clientSock, serverSock) = TestSockets.createConnectedSockets()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = Connection(clientSock, clientScope)
        val server = Connection(serverSock, serverScope)
        try {
            client.start(enableHeartbeat = true, heartbeatIntervalMs = 20L) // 客户端持续发 HEARTBEAT
            server.start(enableHeartbeat = true, heartbeatIntervalMs = 20L, heartbeatTimeoutMs = 120L)
            // 客户端的心跳持续刷新服务端的 lastFrameReceivedAt
            Thread.sleep(600)
            assertTrue("活跃链路不应被看门狗误杀", server.isConnected)
        } finally {
            client.disconnect(); server.disconnect()
            clientScope.cancel(); serverScope.cancel()
        }
    }
}
