package com.dilinkauto.desktop

import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.config.DesktopSettingsStore
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.protocol.CONNECTION_METHOD_SHIZUKU
import com.dilinkauto.protocol.CONNECTION_METHOD_USB_ADB
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeployArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DesktopApp] 的会话生命周期测试（audit WIN-15 ①）。
 *
 * 这是会话生命周期语义的回归防线：
 *  - [DesktopApp.stop] 必须**可等待**（WIN-03）：调用方紧接着 exitProcess，
 *    异步提交会把收尾截断；
 *  - 会话结束（关窗）信号只由**未被下一代顶替**的那一代发出（WIN-04）：否则
 *    "应用并重连"会先结束旧代，旧代的结束信号把窗口误关掉；
 *  - 部署失败必须**终止会话**而不是静默挂着（WIN-06）：可观测信号是状态落到
 *    DISCONNECTED；
 *  - 启动失败（连接被拒/部署失败）**不关窗**（2026-10-09 真机）：从未到过
 *    STREAMING 的会话断开时窗口保留 —— 否则双击 exe 时手机端未启动，3 秒即
 *    整窗退出，形同闪退；只有**到过 STREAMING** 的会话断开才自动关窗。
 *
 * 不依赖真实设备：用"不可达端口"模拟连接失败、用"只接不回"的假服务器模拟挂住、
 * 用"无 Shizuku"的假手机驱动部署失败路径、用"VD 已就绪"的假手机驱动 STREAMING
 * 路径。所有用例都在真 TCP（127.0.0.1）上跑。
 */
class DesktopAppTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── 用例 ───

    @Test
    fun `启动失败连接被拒时不关窗但会话状态落到已断开`() {
        val app = newApp(freePort())
        try {
            app.start()
            val session = awaitSession(app)

            // 端口无人监听 → 连接立即失败 → 会话收尾。会话必须终止且**可观测**
            //（WIN-06：状态落 DISCONNECTED），但不得自动关窗 —— 从未到过
            // STREAMING 的会话关窗 = 双击 exe 3 秒即整窗退出（2026-10-09 真机实测），
            // 窗口要留给用户点「应用并重连」。
            awaitCondition("会话状态落到 DISCONNECTED") {
                session.state.value == SessionState.DISCONNECTED
            }
            assertFalse("启动失败的会话不得触发自动关窗", session.sessionEnded.value)
            assertSame("未被顶替时 session 应保持为这一代", session, app.session.value)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `应用并重连时被顶替的旧代不发出结束信号`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val videoPort = freePort()
        val inputPort = freePort()
        val phone = StreamingPhone(scope, videoPort, inputPort)
        val app = newApp(phone.port, videoPort = videoPort, inputPort = inputPort)
        try {
            app.start()
            val first = awaitSession(app)
            // 旧代必须真正到过 STREAMING：否则"不发结束信号"可能只是启动失败豁免，
            // 代际守卫（WIN-04）就失去了区分度。
            awaitCondition("旧代到过 STREAMING") {
                first.state.value == SessionState.STREAMING
            }

            // 「应用并重连」：closeSession 先摘 _session，再建新代
            app.restart(dpiOverride = null, hwaccelEnabled = null)
            awaitCondition("新代建立") { app.session.value?.generation == 2 }

            // 等旧代协程真正收尾（状态机落到 DISCONNECTED 是收尾路径上的最后一站），
            // 再留一小段缓冲：若守卫失效，sessionEnded 会在 runSession 返回后立即被置真
            awaitCondition("旧代收尾") { first.state.value == SessionState.DISCONNECTED }
            Thread.sleep(150)

            assertFalse("被顶替的旧代不得发出结束信号（WIN-04）", first.sessionEnded.value)
            assertFalse("新代的结束信号也不该被旧代触发", app.session.value!!.sessionEnded.value)
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    @Test
    fun `到过 STREAMING 的会话断开后会自动关窗`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val videoPort = freePort()
        val inputPort = freePort()
        val phone = StreamingPhone(scope, videoPort, inputPort)
        val app = newApp(phone.port, videoPort = videoPort, inputPort = inputPort)
        try {
            app.start()
            val session = awaitSession(app)
            awaitCondition("会话到过 STREAMING") {
                session.state.value == SessionState.STREAMING
            }

            // 手机端断开（关掉假手机 = 全部通道 EOF）→ 会话收尾。
            // 到过 STREAMING 的会话断开 = 用户已经看到过画面，此时自动关窗
            // 是 WIN-04 之后的既定语义（不留一个死掉画面/空窗口）。
            phone.close()
            awaitCondition("当前代发出 sessionEnded") { session.sessionEnded.value }
            assertEquals(SessionState.DISCONNECTED, session.state.value)
        } finally {
            app.stop()
            scope.cancel()
        }
    }

    @Test
    fun `stop 返回时收尾已完成`() {
        val app = newApp(freePort())
        app.start()
        awaitSession(app)

        app.stop()

        // WIN-03 的核心断言：stop() 是同步收尾 —— 返回时当前会话必须已摘除，
        // 这样 DesktopMain 紧接着 exitProcess 才不会截断 closeSession
        assertNull("stop() 返回后 session 必须为 null", app.session.value)
    }

    @Test
    fun `restart 把新参数写进配置并按协议区间夹紧`() {
        val app = newApp(freePort())
        try {
            app.start()
            awaitSession(app)

            // 9999 越界 → 按协议区间夹到 480（WIN-08：与手机侧同一份区间定义）
            app.restart(dpiOverride = 9999, hwaccelEnabled = true)
            awaitCondition("重连完成") { app.session.value?.generation == 2 }

            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, app.startupDpi)
            assertTrue(app.startupHwaccel)

            // 已落盘：下次启动读回同一组值
            val saved = DesktopSettingsStore(configFile).load {}
            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, saved.startupDpi)
            assertTrue(saved.startupHwaccel)

            // null（持续黑屏自动重连用的就是这组参数）= 不改动现值
            app.restart(dpiOverride = null, hwaccelEnabled = null)
            awaitCondition("再次重连完成") { app.session.value?.generation == 3 }
            assertEquals(VdDeployArgs.DPI_OVERRIDE_MAX, app.startupDpi)
            assertTrue(app.startupHwaccel)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `dev_mode 关闭时部署失败会终止会话而不是静默挂着`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = NoShizukuPhone(scope)
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val app = newApp(phone.port, logLines)
        try {
            app.start()
            val session = awaitSession(app)

            // 握手到达假手机（"无 Shizuku"响应已回）→ 桌面端进入部署分支
            awaitCondition("假手机收到握手请求") { phone.handshakeReceived.isCompleted }

            // dev_mode 关闭 → 部署闸门拒绝 → 会话必须终止（WIN-06），而不是
            // 与 UI 一起停在"正在连接 …"看不出死活。终止的可观测信号是状态落
            // DISCONNECTED；自动关窗（sessionEnded）按启动失败豁免**不触发** ——
            // 从未到过 STREAMING 的会话不关窗，窗口留给用户改配置后重试。
            awaitCondition("会话因部署失败而终止（DISCONNECTED）") {
                session.state.value == SessionState.DISCONNECTED
            }
            assertFalse("部署失败（未到过 STREAMING）不得触发自动关窗", session.sessionEnded.value)
            assertTrue(
                "日志里应有 dev_mode 提示",
                synchronized(logLines) { logLines.any { it.contains("dev_mode") } },
            )
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    // ─── D-01：握手响应里的对端可控字段必须被拒绝 ───

    /**
     * 手机下发白名单外的 jar 路径 → 会话终止，且日志给出可读原因。
     *
     * 这是 CRITICAL 修复的端到端断言：`vdServerJarPath` 会原样进设备侧
     * `CLASSPATH=<path> exec app_process ...`。敌意/被占位的 LAN 主机借此在
     * 设备上以 shell 身份跑任意代码。拒绝必须**可见**（终止会话 + 日志说明），
     * 不能是静默兜底换默认路径 —— 那会让用户以为连上了。
     */
    @Test
    fun `敌意 jar 路径被拒绝并终止会话`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = NoShizukuPhone(
            scope,
            vdServerJarPath = "/data/local/tmp/evil-vd-server.jar",
        )
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val app = newApp(
            phone.port,
            logLines,
            settings = DesktopSettings(devMode = true),
        )
        try {
            app.start()
            val session = awaitSession(app)
            awaitCondition("假手机收到握手请求") { phone.handshakeReceived.isCompleted }

            awaitCondition("会话因 jar 路径越界而终止（DISCONNECTED）") {
                session.state.value == SessionState.DISCONNECTED
            }
            assertFalse("越界路径（未到过 STREAMING）不得触发自动关窗", session.sessionEnded.value)
            assertTrue(
                "日志里应说明 jar 路径被拒: ${synchronized(logLines) { logLines.toList() }}",
                synchronized(logLines) { logLines.any { it.contains("白名单") && it.contains("vd-server.jar") } },
            )
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    /**
     * 手机下发非标准 adb 端口 → 会话终止。
     *
     * 端口拼进 `adb connect <host>:<port>`：对端写任意值就能让本端去连它选定的
     * 服务。必须是显式拒绝而不是"夹紧到 5555 继续"。
     */
    @Test
    fun `非标准 adb 端口被拒绝并终止会话`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = NoShizukuPhone(scope, adbPort = 4444)
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val app = newApp(
            phone.port,
            logLines,
            settings = DesktopSettings(devMode = true),
        )
        try {
            app.start()
            val session = awaitSession(app)
            awaitCondition("假手机收到握手请求") { phone.handshakeReceived.isCompleted }

            awaitCondition("会话因端口越界而终止（DISCONNECTED）") {
                session.state.value == SessionState.DISCONNECTED
            }
            assertTrue(
                "日志里应说明端口被拒: ${synchronized(logLines) { logLines.toList() }}",
                synchronized(logLines) { logLines.any { it.contains("adb 端口非法") } },
            )
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    /**
     * 越界的 VD 尺寸必须被钳制而不是原样下发（D-M4 的端到端一面）。
     *
     * 敌意对端在手机上申请超大 VirtualDisplay = 设备 OOM。机器上**有** adb 时
     * `adb connect` 可能返回成功，于是部署会一路走到 launch 才失败，会话随后要等
     * 30s 的 VD-ready 超时才终止（WIN-06 的设计）——所以这里不断言"会话终止"，
     * 而是断言最终 argv:peer 给的 -1 / Int.MAX_VALUE 被钳回视口尺寸，`app_process`
     * 命令行里不出现任何越界值。
     */
    @Test
    fun `越界 VD 尺寸不会走到部署`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val phone = NoShizukuPhone(scope, vdWidth = -1, vdHeight = Int.MAX_VALUE)
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val app = newApp(phone.port, logLines, settings = DesktopSettings(devMode = true))
        try {
            app.start()
            awaitSession(app)
            awaitCondition("假手机收到握手请求") { phone.handshakeReceived.isCompleted }

            awaitCondition("部署进入 app_process 启动阶段") {
                synchronized(logLines) { logLines.any { it.contains("app_process 参数") } }
            }
            val argsLine = synchronized(logLines) { logLines.first { it.contains("app_process 参数") } }
            // 前两个 argv = VD 宽高，走 AdbDeploy.plan 的钳制规则：
            //  - vdWidth=-1(<=0) → 回落本端视口宽(1280,偶数对齐);
            //  - vdHeight=Int.MAX_VALUE(>0) → evenMin2 + coerceIn(2,4096) → 4096
            //    (audit D-M4  sanctioned 的上限：>1080p 会让 439 车机软解崩帧).
            val args = argsLine.substringAfter("app_process 参数").trim().split(" ")
            assertEquals(1280, args[0].toInt())
            assertEquals(4096, args[1].toInt())
            // 负值与 Int.MAX_VALUE 本身都不能以任何形态出现在命令行里
            assertFalse("越界尺寸不得出现在 argv: $argsLine", argsLine.contains("MAX_VALUE"))
            assertFalse("越界尺寸不得出现在 argv: $argsLine", argsLine.contains("2147483647"))
        } finally {
            app.stop()
            phone.close()
            scope.cancel()
        }
    }

    // ─── 辅助 ───

    private val configFile: File get() = File(tmp.root, "config.json")

    private fun newApp(
        controlPort: Int,
        logLines: MutableList<String>? = null,
        videoPort: Int = freePort(),
        inputPort: Int = freePort(),
        settings: DesktopSettings = DesktopSettings(),
    ): DesktopApp {
        val collected = logLines
        val log = DesktopLog(
            file = null,
            fileEnabled = false,
            echoToConsole = collected != null,
            console = { line -> collected?.add(line) },
        )
        return DesktopApp(
            configFile = configFile,
            settings = settings,
            log = log,
            initialConfig = DesktopConfig(
                phoneHost = "127.0.0.1",
                controlPort = controlPort,
                videoPort = videoPort,
                inputPort = inputPort,
                viewportWidth = 1280,
                viewportHeight = 720,
                connectTimeoutMs = 3_000,
            ),
        )
    }

    /** 拿一个"几乎必然无人监听"的端口：OS 分配后立即释放。 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitSession(app: DesktopApp): DesktopApp.Session {
        awaitCondition("会话代建立") { app.session.value != null }
        return app.session.value!!
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("等待超时：$what")
    }
}

/**
 * "VD 已就绪"的假手机：回一条 `connectionMethod=SHIZUKU` 的握手响应（跳过桌面端
 * 的 adb 部署分支），紧接着发 `VD_PORTS_BOUND`，并预先监听好视频/输入两个口。
 *
 * 桌面端状态机在收到 `VD_PORTS_BOUND` 后**立即**置 STREAMING 再连视频/输入口
 * （[DesktopConnectionService.runSession]），且视频/输入口"连上不说话"即可保持
 * 会话存活 —— 因此这条路径能让会话真正到过 STREAMING 且稳定挂住，用于驱动
 * "到过 STREAMING 的会话"的语义（2026-10-09 的关窗豁免 + WIN-04 代际守卫）。
 */
private class StreamingPhone(
    scope: CoroutineScope,
    videoPort: Int,
    inputPort: Int,
) : Closeable {

    private val control = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().reuseAddress = true
        socket().bind(InetSocketAddress(0))
    }

    private val conns = CopyOnWriteArrayList<Connection>()

    /** 视频/输入口的哑监听：accept 后不通信，只让桌面端连接成功、会话保持存活。 */
    private val auxServers = listOf(videoPort, inputPort).map { port ->
        ServerSocketChannel.open().apply {
            configureBlocking(false)
            socket().reuseAddress = true
            socket().bind(InetSocketAddress(port))
        }
    }

    private val response = HandshakeResponse(
        accepted = true,
        deviceName = "StreamingPhone",
        displayWidth = 1280,
        displayHeight = 720,
        virtualDisplayId = 7,
        adbPort = Ports.ADB_PORT,
        connectionMethod = CONNECTION_METHOD_SHIZUKU,
        vdDpi = 240,
    )

    val port: Int get() = (control.localAddress as InetSocketAddress).port

    private val job = scope.launch {
        for (aux in auxServers) {
            launch {
                while (currentCoroutineContext().isActive) {
                    val channel = try {
                        aux.accept()
                    } catch (_: ClosedChannelException) {
                        null
                    }
                    if (channel == null) {
                        delay(10)
                        continue
                    }
                    channel.configureBlocking(false)
                    // 挂住即可：桌面端只要求连接成功，无数据交换。
                }
            }
        }
        while (currentCoroutineContext().isActive) {
            val channel = try {
                control.accept()
            } catch (_: ClosedChannelException) {
                null
            }
            if (channel == null) {
                delay(10)
                continue
            }
            val conn = Connection(channel, scope)
            conns += conn
            conn.onFrames(Channel.CONTROL) { frame ->
                if (frame.messageType != ControlMsg.HANDSHAKE_REQUEST) return@onFrames
                conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, response.encode())
                conn.sendControl(ControlMsg.VD_PORTS_BOUND)
            }
            conn.start(enableHeartbeat = false)
            // 保活：控制口的 watchdog 在 10s 收不到任何帧时会主动断开（见
            // DesktopConnectionService 的心跳约定），WIN-04 用例里"旧代保持存活"
            // 靠它撑过 restart 与断言的整个窗口。
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(2_000)
                    runCatching { conn.sendControl(ControlMsg.HEARTBEAT_ACK) }
                }
            }
        }
    }

    override fun close() {
        runCatching { control.close() }
        auxServers.forEach { runCatching { it.close() } }
        job.cancel()
        conns.forEach { runCatching { it.disconnect() } }
    }
}

/**
 * 无 Shizuku 的假手机：只接控制口，回一条 `connectionMethod=USB_ADB` 的接受响应，
 * **不**发 `VD_PORTS_BOUND`。驱动的正是"由本端用 adb 部署 VD"那条路径 ——
 * 部署闸门（dev_mode）拒绝后，桌面端应当立刻终止会话。
 */
private class NoShizukuPhone(
    scope: CoroutineScope,
    private val adbPort: Int = Ports.ADB_PORT,
    private val vdServerJarPath: String = "",
    private val vdWidth: Int = 0,
    private val vdHeight: Int = 0,
) : Closeable {

    private val server = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().reuseAddress = true
        socket().bind(InetSocketAddress(0))
    }

    val port: Int get() = (server.localAddress as InetSocketAddress).port

    val handshakeReceived = CompletableDeferred<Unit>()

    private val job = scope.launch {
        val sock = awaitAccept() ?: return@launch
        val conn = Connection(sock, scope)
        conn.onFrames(Channel.CONTROL) { frame ->
            if (frame.messageType != ControlMsg.HANDSHAKE_REQUEST) return@onFrames
            handshakeReceived.complete(Unit)
            conn.sendControl(
                ControlMsg.HANDSHAKE_RESPONSE,
                HandshakeResponse(
                    accepted = true,
                    deviceName = "NoShizukuPhone",
                    displayWidth = 1280,
                    displayHeight = 720,
                    virtualDisplayId = 7,
                    adbPort = adbPort,
                    vdServerJarPath = vdServerJarPath,
                    connectionMethod = CONNECTION_METHOD_USB_ADB,
                    vdDpi = 240,
                    vdWidth = vdWidth,
                    vdHeight = vdHeight,
                ).encode(),
            )
        }
        conn.start(enableHeartbeat = false)
    }

    private suspend fun awaitAccept(): SocketChannel? {
        while (currentCoroutineContext().isActive) {
            try {
                server.accept()?.let { return it }
            } catch (_: ClosedChannelException) {
                return null
            }
            delay(10)
        }
        return null
    }

    override fun close() {
        runCatching { server.close() }
        job.cancel()
    }
}
