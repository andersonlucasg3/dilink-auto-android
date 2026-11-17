package com.dilinkauto.desktop

import com.dilinkauto.desktop.apps.AppCatalog
import com.dilinkauto.desktop.apps.TestIcons
import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.AppListMessage
import com.dilinkauto.protocol.CONNECTION_METHOD_SHIZUKU
import com.dilinkauto.protocol.CONNECTION_METHOD_USB_ADB
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.HandshakeRequest
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.PROTOCOL_VERSION
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import com.dilinkauto.protocol.VideoConfig
import com.dilinkauto.protocol.VideoMsg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DesktopConnectionService] 的集成测试：用 [FakePhone] 在独立端口上模拟手机侧，
 * 跑通"握手 → VD_PORTS_BOUND → 连视频/输入口 → 持续收帧"的完整时序。
 *
 * 刻意用真实 IO（127.0.0.1）而非纯 mock，因为这里要验证的正是：
 *  - 握手报文真的能被对端解码（字段 + 偶数对齐）；
 *  - 三连接时序（视频/输入必须在 VD_PORTS_BOUND 之后才连）；
 *  - 状态机推进顺序。
 */
class DesktopConnectionServiceTest {

    // 固定端口而非 0，方便排查；与协议默认端口（9637/9638/9639）错开，避免误连真实设备。
    private val controlPort = 29637
    private val videoPort = 29638
    private val inputPort = 29639

    private fun config() = DesktopConfig(
        phoneHost = "127.0.0.1",
        controlPort = controlPort,
        videoPort = videoPort,
        inputPort = inputPort,
        viewportWidth = 1281, // 奇数，验证偶数对齐
        viewportHeight = 721,
    )

    @Test
    fun evenAlign_floorsToEvenAndKeepsMinimumOfTwo() {
        assertEquals(1280, HandshakeFactory.evenAlign(1281))
        assertEquals(720, HandshakeFactory.evenAlign(721))
        assertEquals(1280, HandshakeFactory.evenAlign(1280))
        assertEquals(2, HandshakeFactory.evenAlign(1))
        assertEquals(2, HandshakeFactory.evenAlign(0))
    }

    @Test
    fun fullSession_handshakesThenStreamsVideo() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(scope, controlPort, videoPort, inputPort)
        phone.start()

        val service = DesktopConnectionService(scope, config())
        val states = mutableListOf<SessionState>()
        val response = CompletableDeferred<HandshakeResponse>()
        val frames = AtomicInteger()
        service.onStateChanged = { synchronized(states) { states.add(it) } }
        service.onHandshakeResponse = { response.complete(it) }
        service.onVideoFrame = { frames.incrementAndGet() }

        val sessionJob = scope.launch { service.runSession() }
        try {
            // 1) 握手请求到达假手机，且视口做了偶数对齐、其余字段来自配置。
            val req = withTimeout(5_000) { phone.handshakeRequest.await() }
            assertEquals(PROTOCOL_VERSION, req.protocolVersion)
            assertEquals(DesktopConfig.DEFAULT_DEVICE_NAME, req.deviceName)
            assertEquals(1280, req.screenWidth)
            assertEquals(720, req.screenHeight)
            assertEquals(DesktopConfig.DEFAULT_SCREEN_DPI, req.screenDpi)
            assertEquals(DesktopConfig.DESKTOP_APP_VERSION_CODE, req.appVersionCode)
            assertEquals(VideoConfig.TARGET_FPS, req.targetFps)
            assertEquals(VideoConfig.DEFAULT_BITRATE, req.bitrate)

            // 2) 假手机的响应被正确解码。
            val resp = withTimeout(5_000) { response.await() }
            assertTrue(resp.accepted)
            assertEquals("FakePhone", resp.deviceName)
            assertEquals(CONNECTION_METHOD_SHIZUKU, resp.connectionMethod)
            assertEquals(240, resp.vdDpi)

            // 3) 视频/输入口在 VD_PORTS_BOUND 之后被连上。
            withTimeout(5_000) { phone.inputAccepted.await() }

            // 4) 视频口持续推流。
            withTimeout(5_000) {
                while (frames.get() < 3) delay(20)
            }

            // 5) 状态机走到 STREAMING，且各阶段顺序正确。
            assertEquals(SessionState.STREAMING, service.state)
            val snapshot = synchronized(states) { states.toList() }
            assertTrue("missing states: $snapshot", snapshot.containsAll(
                listOf(
                    SessionState.CONNECTING,
                    SessionState.HANDSHAKING,
                    SessionState.WAITING_VD,
                    SessionState.STREAMING,
                )
            ))
            assertTrue(snapshot.indexOf(SessionState.HANDSHAKING) < snapshot.indexOf(SessionState.WAITING_VD))
            assertTrue(snapshot.indexOf(SessionState.WAITING_VD) < snapshot.indexOf(SessionState.STREAMING))
        } finally {
            service.stop()
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }

    /** Phase 2 集成：鼠标手势与导航命令要从输入口（9639）原样到达手机侧。 */
    @Test
    fun inputEvents_reachPhoneOverInputPort() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(scope, controlPort, videoPort, inputPort)
        phone.start()

        val service = DesktopConnectionService(scope, config())
        val sessionJob = scope.launch { service.runSession() }
        try {
            withTimeout(5_000) { phone.inputAccepted.await() }
            // 输入通道在连上输入口之后才被注入
            withTimeout(5_000) { while (!service.inputSender.isConnected) delay(10) }

            service.inputSender.down(0.25f, 0.5f)
            service.inputSender.move(0.30f, 0.55f)
            service.inputSender.up(0.35f, 0.60f)
            service.inputSender.goHome()
            service.inputSender.launchApp("com.autonavi.minimap")
            // Phase 5e：手机物理屏电源开关，与导航命令同走输入口的 CONTROL 通道
            service.inputSender.setDisplayPower(false)

            withTimeout(5_000) { while (phone.inputFrames.size < 6) delay(10) }

            // 单条 TCP，到达顺序即发送顺序
            val frames = phone.inputFrames.toList()
            val touches = frames.filter { it.channel == Channel.INPUT }
            assertEquals(3, touches.size)
            assertEquals(InputMsg.TOUCH_DOWN, touches[0].messageType)
            assertEquals(0.25f, TouchEvent.decode(touches[0].payload).x, 0.0001f)
            assertEquals(InputMsg.TOUCH_MOVE_BATCH, touches[1].messageType)
            assertEquals(0.30f, TouchMoveBatch.decode(touches[1].payload).pointers[0].x, 0.0001f)
            assertEquals(InputMsg.TOUCH_UP, touches[2].messageType)

            val commands = frames.filter { it.channel == Channel.CONTROL }
            // Connection 对 INPUT/CONTROL 内联按到达顺序派发、发送侧走单线程派发器，
            // 所以触摸与命令都应严格保持调用顺序。
            assertEquals(
                listOf(ControlMsg.GO_HOME, ControlMsg.LAUNCH_APP, ControlMsg.SET_DISPLAY_POWER),
                commands.map { it.messageType },
            )
            assertEquals("com.autonavi.minimap", LaunchAppMessage.decode(commands[1].payload).packageName)
            // Phase 5e：屏幕电源载荷固定为单字节（0=熄屏），要与 GO_HOME 的空载荷区分开
            assertArrayEquals(byteArrayOf(0), commands[2].payload)
        } finally {
            service.stop()
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }

    /**
     * Phase 3 集成：手机推来的 `APP_LIST` / `APP_UNINSTALLED`（走控制口 DATA 通道）
     * 要一路走到 [com.dilinkauto.desktop.apps.AppCatalog]，且图标被解码成位图。
     */
    @Test
    fun appListOverDataChannel_reachesCatalog() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(scope, controlPort, videoPort, inputPort)
        phone.start()

        val service = DesktopConnectionService(scope, config())
        val catalog = AppCatalog()
        service.onAppList = { catalog.onAppList(it) }
        service.onAppUninstalled = { catalog.onUninstalled(it) }

        val sessionJob = scope.launch { service.runSession() }
        try {
            withTimeout(5_000) { phone.handshakeRequest.await() }
            // 等握手响应被处理完（服务已进入 WAITING_VD），再推数据帧
            withTimeout(5_000) {
                while (service.state != SessionState.WAITING_VD && service.state != SessionState.STREAMING) delay(10)
            }

            phone.sendAppList(
                AppListMessage(
                    listOf(
                        AppInfo("com.autonavi.minimap", "高德地图", AppCategory.NAVIGATION, TestIcons.pngBytes()),
                        // 没带图标 → 目录里 icon 为 null，UI 用首字母占位
                        AppInfo("com.tencent.mm", "微信", AppCategory.COMMUNICATION),
                    )
                )
            )

            withTimeout(5_000) { while (catalog.apps.value.size < 2) delay(10) }
            val apps = catalog.apps.value
            assertEquals(listOf("com.autonavi.minimap", "com.tencent.mm"), apps.map { it.packageName })
            assertEquals("高德地图", apps[0].appName)
            assertNotNull(apps[0].icon)
            assertNull(apps[1].icon)

            // 卸载推送 → 列表与图标缓存同步移除
            phone.sendAppUninstalled("com.autonavi.minimap")
            withTimeout(5_000) { while (catalog.apps.value.size != 1) delay(10) }
            assertEquals(listOf("com.tencent.mm"), catalog.apps.value.map { it.packageName })
        } finally {
            service.stop()
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun rejectedHandshake_neverReachesStreaming() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(scope, controlPort, videoPort, inputPort, acceptHandshake = false)
        phone.start()

        val service = DesktopConnectionService(scope, config())
        var responseDelivered = false
        service.onHandshakeResponse = { responseDelivered = true }

        val sessionJob = scope.launch { service.runSession() }
        try {
            withTimeout(5_000) { phone.handshakeRequest.await() }
            // 被拒后 runSession 应自行收尾到 DISCONNECTED，且不把响应抛给上层。
            withTimeout(5_000) {
                while (service.state != SessionState.DISCONNECTED) delay(20)
            }
            assertFalse(responseDelivered)
        } finally {
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }

    /**
     * Phase 5c 集成：手机回告"无 Shizuku"的 ADB 连接方式时，桌面端要先跑 VD 部署钩子，
     * 且**部署完成前不能去连视频/输入口**（那两个口只有在 `VD_PORTS_BOUND` 之后才监听）。
     */
    @Test
    fun usbAdbHandshake_deploysVdServerBeforeConnectingVideo() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(
            scope, controlPort, videoPort, inputPort,
            connectionMethod = CONNECTION_METHOD_USB_ADB,
            adbPort = 5555,
            vdServerJarPath = "/sdcard/DiLinkAuto/vd-server.jar",
        )
        phone.start()

        val service = DesktopConnectionService(scope, config())
        val deployRequested = CompletableDeferred<HandshakeResponse>()
        val releaseDeploy = CompletableDeferred<Unit>()
        // 挂起回调：模拟"adb 部署耗时"，期间会话必须停在这里
        service.onVdDeployRequired = { response ->
            deployRequested.complete(response)
            releaseDeploy.await()
        }

        val sessionJob = scope.launch { service.runSession() }
        try {
            // 1) 钩子被调用，且响应带着部署所需的信息（adb 端口 + 手机侧 jar 路径）
            val resp = withTimeout(5_000) { deployRequested.await() }
            assertEquals(CONNECTION_METHOD_USB_ADB, resp.connectionMethod)
            assertEquals(5555, resp.adbPort)
            assertEquals("/sdcard/DiLinkAuto/vd-server.jar", resp.vdServerJarPath)

            // 2) 部署未完成 → 还停在 HANDSHAKING，视频/输入口都没被连
            assertEquals(SessionState.HANDSHAKING, service.state)
            assertFalse("部署完成前不该连视频口", phone.videoAccepted.isCompleted)
            assertFalse("部署完成前不该连输入口", phone.inputAccepted.isCompleted)

            // 3) 放行部署 → 才继续等 VD_PORTS_BOUND → 连视频/输入口 → STREAMING
            releaseDeploy.complete(Unit)
            withTimeout(5_000) { phone.inputAccepted.await() }
            withTimeout(5_000) { phone.videoAccepted.await() }
            withTimeout(5_000) { while (service.state != SessionState.STREAMING) delay(10) }
        } finally {
            service.stop()
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }

    /**
     * D-L3：[stop] 必须等 teardown 真的跑完再返回。
     *
     * 旧实现只 complete 了三个 await 信号，teardown 仍在 IO 协程上异步续跑 ——
     * [DesktopApp.closeSession] 紧接着停解码器的顺序事实上不成立（会话协程可能
     * 还在往已关闭的连接上派发帧）。这里断言 stop() 返回后 teardown 已收尾。
     */
    @Test
    fun stop_awaitsTeardownBeforeReturning() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = FakePhone(scope, controlPort, videoPort, inputPort)
        phone.start()

        val service = DesktopConnectionService(scope, config())
        val sessionJob = scope.launch { service.runSession() }
        try {
            withTimeout(5_000) { phone.handshakeRequest.await() }
            withTimeout(5_000) { while (service.state != SessionState.STREAMING) delay(10) }

            service.stop()

            // teardown 的最后一步就是把状态置 DISCONNECTED —— 它已同步完成
            assertEquals(SessionState.DISCONNECTED, service.state)
            assertFalse("teardown 后输入通道应被摘除", service.inputSender.isConnected)
        } finally {
            phone.stop()
            sessionJob.cancel()
            scope.cancel()
        }
    }
}

/**
 * 假手机：只实现桌面端 v1 依赖的最小协议面。
 *
 * 三个独立端口分别扮演手机侧三类连接，握手时序与真机一致：
 *  控制口：读 HANDSHAKE_REQUEST → 回 HANDSHAKE_RESPONSE → 发 VD_PORTS_BOUND
 *  视频口：接受连接后按目标帧率持续推假帧（Phase 1a 不需要合法 H.264）
 *  输入口：接受连接即可（事件校验留给 Phase 2）
 */
private class FakePhone(
    private val scope: CoroutineScope,
    private val controlPort: Int,
    private val videoPort: Int,
    private val inputPort: Int,
    private val acceptHandshake: Boolean = true,
    /** 握手响应里回告的连接方式；SHIZUKU 由手机自部署，其余由桌面端部署（Phase 5c）。 */
    private val connectionMethod: Byte = CONNECTION_METHOD_SHIZUKU,
    private val adbPort: Int = Ports.ADB_PORT,
    private val vdServerJarPath: String = "",
) {
    val handshakeRequest = CompletableDeferred<HandshakeRequest>()
    val videoAccepted = CompletableDeferred<Unit>()
    val inputAccepted = CompletableDeferred<Unit>()

    /**
     * 输入口收到的全部帧（INPUT 触摸 + CONTROL 命令）。
     *
     * 用 CopyOnWriteArrayList：写入在 IO 线程的帧分发协程里，读取在测试线程里，
     * 且测试轮询期间会反复 toList() 快照。
     */
    val inputFrames = CopyOnWriteArrayList<FrameCodec.Frame>()

    private var controlServer: ServerSocketChannel? = null
    private var videoServer: ServerSocketChannel? = null
    private var inputServer: ServerSocketChannel? = null

    /** 控制口连接：测试用它主动推 DATA 帧（APP_LIST / APP_UNINSTALLED）。 */
    @Volatile
    private var controlConn: Connection? = null

    /** 模拟手机推全量应用列表（走控制口的 DATA 通道）。 */
    fun sendAppList(message: AppListMessage) {
        controlConn?.sendData(DataMsg.APP_LIST, message.encode())
    }

    /** 模拟手机推"应用已卸载"（载荷为包名字符串）。 */
    fun sendAppUninstalled(packageName: String) {
        controlConn?.sendData(DataMsg.APP_UNINSTALLED, packageName.toByteArray(Charsets.UTF_8))
    }

    /**
     * 先同步绑定三个端口，再启动 accept 循环。
     *
     * 关键：bind 必须发生在 [start] 返回之前。若只在协程里 `Connection.accept`，
     * 测试可能在端口尚未 bind 时就发起连接，导致 connect 直接失败（用例随机超时）。
     */
    fun start() {
        val control = bind(controlPort)
        val video = bind(videoPort)
        val input = bind(inputPort)
        controlServer = control
        videoServer = video
        inputServer = input
        scope.launch { serveControl(control) }
        scope.launch { serveVideo(video) }
        scope.launch { serveInput(input) }
    }

    /**
     * 同步关闭三个监听口。
     *
     * 必须同步：协程取消是异步的，若只依赖 `scope.cancel()` 触发 finally，
     * 下个用例可能在端口尚未释放时就 bind，抛出 BindException。
     */
    fun stop() {
        runCatching { controlServer?.close() }
        runCatching { videoServer?.close() }
        runCatching { inputServer?.close() }
        controlServer = null
        videoServer = null
        inputServer = null
    }

    private fun bind(port: Int): ServerSocketChannel =
        ServerSocketChannel.open().apply {
            configureBlocking(false)
            socket().reuseAddress = true
            socket().bind(InetSocketAddress(port))
        }

    /** 阻塞等待一个连接；监听口被关闭 / 协程取消时返回 null。 */
    private suspend fun awaitConnection(server: ServerSocketChannel): SocketChannel? {
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    server.accept()?.let { return it }
                } catch (_: ClosedChannelException) {
                    return null // stop() 关闭了监听口
                }
                delay(10)
            }
            return null
        } finally {
            runCatching { server.close() }
        }
    }

    private suspend fun serveControl(server: ServerSocketChannel) {
        val sock = awaitConnection(server) ?: return
        val conn = Connection(sock, scope)
        controlConn = conn
        conn.onFrames(Channel.CONTROL) { frame ->
            if (frame.messageType != ControlMsg.HANDSHAKE_REQUEST) return@onFrames
            val req = HandshakeRequest.decode(frame.payload)
            handshakeRequest.complete(req)
            conn.sendControl(
                ControlMsg.HANDSHAKE_RESPONSE,
                HandshakeResponse(
                    accepted = acceptHandshake,
                    deviceName = "FakePhone",
                    displayWidth = req.screenWidth,
                    displayHeight = req.screenHeight,
                    virtualDisplayId = 7,
                    adbPort = adbPort,
                    vdServerJarPath = vdServerJarPath,
                    connectionMethod = connectionMethod,
                    vdDpi = 240,
                ).encode(),
            )
            if (acceptHandshake) {
                conn.sendControl(ControlMsg.VD_PORTS_BOUND, ByteArray(0))
            }
        }
        conn.start(enableHeartbeat = false)
    }

    private suspend fun serveVideo(server: ServerSocketChannel) {
        val sock = awaitConnection(server) ?: return
        val conn = Connection(sock, scope)
        conn.start(enableHeartbeat = false)
        videoAccepted.complete(Unit)
        while (currentCoroutineContext().isActive) {
            conn.sendVideo(VideoMsg.FRAME, ByteArray(64))
            delay(VideoConfig.FRAME_INTERVAL_MS)
        }
    }

    private suspend fun serveInput(server: ServerSocketChannel) {
        val sock = awaitConnection(server) ?: return
        val conn = Connection(sock, scope)
        // 真机侧 PipelineServer.readTouchAndCommands 在同一条输入口连接上
        // 同时读 INPUT（触摸）和 CONTROL（导航/启动命令），这里照抄该行为。
        conn.onFrames(Channel.INPUT) { inputFrames.add(it) }
        conn.onFrames(Channel.CONTROL) { inputFrames.add(it) }
        conn.start(enableHeartbeat = false)
        inputAccepted.complete(Unit)
        while (currentCoroutineContext().isActive) delay(100) // 保持连接存活直到测试收尾
    }
}