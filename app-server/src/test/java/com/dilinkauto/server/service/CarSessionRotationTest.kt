package com.dilinkauto.server.service

import com.dilinkauto.protocol.Connection
import com.dilinkauto.server.decoder.AudioPlayer
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 校验屏幕旋转重握手时的多通道连接与音频生命周期清理（issue #126）。
 */
class CarSessionRotationTest {

    private fun allocateCoordinator(): CarSessionCoordinator {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, CarSessionCoordinator::class.java) as CarSessionCoordinator
    }

    private fun createConnectedSockets(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port
        val client = SocketChannel.open()
        client.connect(InetSocketAddress("127.0.0.1", port))
        val accepted = server.accept()
        server.close()
        return Pair(client, accepted)
    }

    private fun createConnection(scope: CoroutineScope): Connection {
        val (client, _) = createConnectedSockets()
        return Connection(client, scope)
    }

    @Test
    fun rehandshakeOnExistingControlLocked_cleansUpAudioConnectionAndStopsPlayer() = runBlocking {
        val coordinator = allocateCoordinator()
        val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val player = AudioPlayer()
        var loggedStopping = false
        player.logSink = { if (it.contains("Stopping")) loggedStopping = true }
        // 标记 player 为运行状态
        val runningField = AudioPlayer::class.java.getDeclaredField("running").apply { isAccessible = true }
        (runningField.get(player) as AtomicBoolean).set(true)
        assertTrue(player.isRunning)

        val audioPlayerField = CarSessionCoordinator::class.java.getDeclaredField("audioPlayer").apply { isAccessible = true }
        audioPlayerField.set(coordinator, player)

        val directExecutor = Executors.newSingleThreadExecutor()
        val audioExecutorField = CarSessionCoordinator::class.java.getDeclaredField("audioExecutor").apply { isAccessible = true }
        audioExecutorField.set(coordinator, directExecutor)

        val videoDecoderField = CarSessionCoordinator::class.java.getDeclaredField("videoDecoder").apply { isAccessible = true }
        videoDecoderField.set(coordinator, VideoDecoder())

        val contextField = CarSessionCoordinator::class.java.getDeclaredField("context").apply { isAccessible = true }
        contextField.set(coordinator, android.content.ContextWrapper(null))

        val stateField = CarSessionCoordinator::class.java.getDeclaredField("_state").apply { isAccessible = true }
        stateField.set(coordinator, MutableStateFlow(CarConnectionService.State.CONNECTED))

        val statusField = CarSessionCoordinator::class.java.getDeclaredField("_statusMessage").apply { isAccessible = true }
        statusField.set(coordinator, MutableStateFlow(""))

        val videoReadyField = CarSessionCoordinator::class.java.getDeclaredField("_videoReady").apply { isAccessible = true }
        videoReadyField.set(coordinator, MutableStateFlow(true))

        // issue #198：新依赖（等 Surface 诊断节奏），Unsafe 构造不会跑字段初始化器。
        val surfaceWaitField = CarSessionCoordinator::class.java.getDeclaredField("surfaceWait").apply { isAccessible = true }
        surfaceWaitField.set(coordinator, com.dilinkauto.server.decoder.SurfaceWaitLogger())

        // 配置各路真实连接
        val ctrlConn = createConnection(testScope)
        val videoConn = createConnection(testScope)
        val inputConn = createConnection(testScope)
        val audioConn = createConnection(testScope)

        assertTrue(audioConn.isConnected)

        coordinator.controlConnection = ctrlConn
        coordinator.videoConnection = videoConn
        coordinator.inputConnection = inputConn
        coordinator.audioConnection = audioConn

        // 模拟屏幕旋转触发重握手
        try {
            coordinator.rehandshakeOnExistingControlLocked(ctrlConn, 1920, 1080, 160)
        } catch (_: Exception) {
            // ctrlConn 上后续握手协议数据写入未开启接收端，可能抛异常，忽略之
        }

        // 验证各连接已被置空
        assertNull("旋转重握手必须将 audioConnection 置空", coordinator.audioConnection)
        assertNull("旋转重握手必须将 videoConnection 置空", coordinator.videoConnection)
        assertNull("旋转重握手必须将 inputConnection 置空", coordinator.inputConnection)

        // 等待 audioExecutor 执行任务
        directExecutor.shutdown()
        assertTrue("audioExecutor 应在 2 秒内排空", directExecutor.awaitTermination(2, TimeUnit.SECONDS))

        // 验证旧 audioConnection 已被 disconnect
        assertFalse("旋转重握手必须断开旧的 audioConnection", audioConn.isConnected)

        // 验证旧 audioPlayer 已被 stop
        assertFalse("旋转重握手必须停止旧的 audioPlayer", player.isRunning)
        assertTrue("必须输出停止日志", loggedStopping)
    }
}
