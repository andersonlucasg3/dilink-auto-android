package com.dilinkauto.desktop

import com.dilinkauto.desktop.apps.AppCatalog
import com.dilinkauto.desktop.apps.AppEntry
import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.config.DesktopSettingsStore
import com.dilinkauto.desktop.deploy.AdbDeploy
import com.dilinkauto.desktop.deploy.AdbDeployer
import com.dilinkauto.desktop.display.KeepAwake
import com.dilinkauto.desktop.input.InputSender
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.desktop.ui.SwingVideoView
import com.dilinkauto.desktop.video.FrameDumper
import com.dilinkauto.desktop.video.VideoDecodePipeline
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.VdDeployArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * 窗口模式的运行时编排（组合根）。
 *
 * 为什么要有"会话代"（[Session]）：握手参数（视口 / DPI / 帧率 / 码率）只在
 * 连接建立时发一次，改了就必须**重连**。所以 [restart] 的做法是把当前一代整个
 * 关掉（停会话 → 手机侧回收 VD → 停解码线程）再按新配置建一代，UI 靠
 * [Session.generation] 判断"画面组件要不要换"。
 *
 * 生命周期操作全部串行在一根专用线程上（[lifecycle]）：`stop()` 里会 join 解码
 * 线程，放在 UI 线程上点一下"应用"就会卡住窗口。窗口关闭时的 [stop] 是**可等待**的
 * ——调用方紧接着就 `exitProcess(0)`（audit WIN-03）。
 *
 * 三处"会话生命周期"的约定（都是踩过坑的，改动前先读）：
 *  - 会话结束信号只由**未被下一代顶替**的那一代发出（audit WIN-04）：
 *    [closeSession] 先摘 `_session`，协程侧再比对身份，避免"应用并重连"误关窗口；
 *  - 且只由**到过 STREAMING** 的会话发出（2026-10-09）：启动失败（连接被拒、
 *    部署失败）不关窗 —— 用户还没看到任何画面就整窗退出，等于"闪退"；
 *  - 部署失败/等待 VD 超时都会**终止会话**而不是静默挂着（audit WIN-06）；
 *  - 持续黑屏最多自动重连 [MAX_BLACK_SCREEN_RECONNECTS] 次（audit WIN-07）。
 *
 * 职责边界：本类只管"把 [DesktopConnectionService] / [VideoDecodePipeline] /
 * [AppCatalog] / 窗口组件接起来"，协议、解码、渲染各自在自己的类里。
 */
class DesktopApp(
    private val configFile: File,
    private var settings: DesktopSettings,
    private val log: DesktopLog,
    initialConfig: DesktopConfig,
) {

    /**
     * 一次连接的全部运行时对象。重启后整代替换 —— UI 收集 [session] 拿到当前代，
     * 用 [generation] 当 key 决定是否重建 Swing 视频组件。
     */
    class Session internal constructor(
        val generation: Int,
        val videoView: SwingVideoView,
        val apps: StateFlow<List<AppEntry>>,
        val sessionEnded: StateFlow<Boolean>,
        val state: StateFlow<SessionState>,
        /** 当前是否真在用硬解；回退软解后变 false（Phase 5b）。 */
        val hardwareDecode: StateFlow<Boolean>,
        /** 会话异常终止的原因（失败面板显示用，2026-10-10）；取消/正常结束保持 null。 */
        val lastError: StateFlow<String?>,
        private val input: InputSender,
    ) {
        fun launchApp(packageName: String) = input.launchApp(packageName)
        fun goHome() = input.goHome()
        fun goBack() = input.goBack()
        fun goRecent() = input.goRecent()

        /** 开关手机物理屏（Phase 5e）；未连上输入口时返回 false，命令被丢弃。 */
        fun setDisplayPower(on: Boolean) = input.setDisplayPower(on)
    }

    private var config: DesktopConfig = initialConfig

    /** config.json 的读写在 [DesktopSettingsStore]（audit R3-SRP-16）。 */
    private val settingsStore = DesktopSettingsStore(configFile)

    /** 当前代是否启用硬解（来自 settings，可在显示面板里改）。 */
    private var hwaccel: Boolean = settings.startupHwaccel

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val keepAwake = KeepAwake()

    private val _keepAwakeOn = MutableStateFlow(settings.keepAwake)

    /** 会话期间是否阻止本机息屏（Phase 5d）；UI 开关写这里。 */
    val keepAwakeOn: StateFlow<Boolean> = _keepAwakeOn.asStateFlow()

    /** 本机能否阻止息屏（非 Windows / JNA 不可用时为 false，UI 据此禁用开关）。 */
    val keepAwakeSupported: Boolean = keepAwake.isSupported

    /** 连接目标（窗口标题用）。 */
    val phoneHost: String get() = config.phoneHost

    /** 握手视口尺寸（窗口初始尺寸用）。 */
    val viewportWidth: Int get() = config.viewportWidth
    val viewportHeight: Int get() = config.viewportHeight

    /** 显示面板的初值；改这两个值需要走 [restart]（握手参数只在建连时发一次）。 */
    val startupDpi: Int get() = settings.startupDpi
    val startupHwaccel: Boolean get() = settings.startupHwaccel

    private val adbDeployer = AdbDeployer()
    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DesktopApp").apply { isDaemon = true }
    }
    private val stopped = AtomicBoolean(false)

    /**
     * 本进程内已自动发起的"持续黑屏重连"次数（audit WIN-07）。
     *
     * 全局计数而不是按会话：检测器的"只升级一次"闩锁每个新会话都会重置，
     * 手机**合法地**显示暗色/黑屏时会一路重连下去。见 [onSustainedBlackScreen]。
     */
    private val blackScreenReconnects = AtomicInteger(0)

    private var scope: CoroutineScope? = null
    private var service: DesktopConnectionService? = null
    private var pipeline: VideoDecodePipeline? = null
    private var generation = 0

    /** 建第一代会话。 */
    fun start() = lifecycle.execute { openSession() }

    /**
     * 改"需要重新握手才能生效"的参数并重连。
     *
     * @param dpiOverride 新的 DPI 覆盖（0=自动）；null 表示不改
     * @param hwaccelEnabled 是否启用 D3D11VA 硬解；null 表示不改
     */
    fun restart(dpiOverride: Int?, hwaccelEnabled: Boolean?) = lifecycle.execute {
        val next = settings.copy(
            // DPI 按协议区间夹紧（audit WIN-08）：区间单点定义在 VdDeployArgs，
            // 桌面端此前自己夹 0..640，与手机侧 120..480 不一致。
            startupDpi = dpiOverride?.let(VdDeployArgs::coerceDpiOverride) ?: settings.startupDpi,
            startupHwaccel = hwaccelEnabled ?: settings.startupHwaccel,
        )
        settings = next
        settingsStore.save(next) { log.warn("config", it) }
        config = config.copy(dpiOverride = next.startupDpi)
        hwaccel = next.startupHwaccel
        log.info(TAG, "重连：dpiOverride=${config.dpiOverride} hwaccel=$hwaccel")
        openSession()
    }

    /** 切换"会话期间保持本机常亮"（Phase 5d）。 */
    fun setKeepAwake(enabled: Boolean) {
        // 与 [restart] 一样走 [lifecycle] 单线程（audit D-02）：settings 的读写
        // （含 settingsStore.save 落盘）此前分散在 lifecycle 线程与 Compose UI
        // 线程，`setKeepAwake` 与 `restart(dpiOverride)` 交错会把用户的 DPI 修改
        // 静默丢掉。本方法可能被 UI 线程调用，所以把整个"改值+落盘"推回 lifecycle。
        lifecycle.execute {
            val next = settings.copy(keepAwake = enabled)
            settings = next
            settingsStore.save(next) { log.warn("config", it) }
            _keepAwakeOn.value = enabled
            log.info(TAG, "保持常亮：$enabled")
            applyKeepAwake()
        }
    }

    /** 关闭窗口时的收尾：停会话（触发手机回收 VD）→ 停解码 → 关闭所有后台资源。 */
    fun stop() {
        // 可等待的收尾（audit WIN-03）：调用方（DesktopMain）紧接着就 exitProcess(0)，
        // 异步提交会让 closeSession()（含解码线程 join，最坏 2s）与 adb 子进程清理
        // 被 JVM 退出截断。注意：本方法不得在 [lifecycle] 线程上调用（会自等）。
        val done = lifecycle.submit {
            if (stopped.compareAndSet(false, true)) {
                closeSession()
                keepAwake.close()
            }
        }
        runCatching { done.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        lifecycle.shutdown()
    }

    // ─── 会话代管理 ───

    private fun openSession() {
        if (stopped.get()) return
        closeSession()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val service = DesktopConnectionService(scope, config)
        // SwingVideoView 必须在 EDT 上创建（audit D-L5）：它内部设 opaque、加
        // 鼠标监听、建 Graphics——Swing 组件的构造与状态修改受 EDT 约束，在
        // EDT 外构造可能出现渲染/焦点/监听器的可见性错乱。本方法跑在 lifecycle
        // 线程上，所以用 invokeAndWait 把构造搬回 EDT（只此一次，阻塞可接受）。
        val videoView = runOnEdt {
            SwingVideoView(
                onTouchDown = { x, y -> service.inputSender.down(x, y) },
                onTouchMove = { x, y -> service.inputSender.move(x, y) },
                onTouchUp = { x, y -> service.inputSender.up(x, y) },
            )
        }

        val catalog = AppCatalog()
        service.onAppList = { catalog.onAppList(it) }
        service.onAppUninstalled = { catalog.onUninstalled(it) }
        service.onLog = { log.info("session", it) }
        service.onHandshakeResponse = { logHandshake(log, it) }

        val sessionEnded = MutableStateFlow(false)
        val stateFlow = MutableStateFlow(SessionState.IDLE)
        val hardwareFlow = MutableStateFlow(hwaccel)
        // 失败面板显示用（2026-10-10）：runSession 的 catch 汇聚所有失败原因。
        val errorFlow = MutableStateFlow<String?>(null)
        service.onSessionError = { errorFlow.value = it }
        // 本会话是否真正到过 STREAMING —— "断开自动关窗"的豁免判据（见下方协程）。
        val reachedStreaming = java.util.concurrent.atomic.AtomicBoolean(false)
        service.onStateChanged = { state ->
            log.info("state", "$state")
            stateFlow.value = state
            if (state == SessionState.STREAMING) reachedStreaming.set(true)
            // 只有真正开始推流才申请常亮；断开即释放，避免"连不上却一直亮着"。
            when (state) {
                SessionState.STREAMING -> applyKeepAwake()
                SessionState.DISCONNECTED -> keepAwake.disable()
                else -> Unit
            }
        }

        // Phase 5c：手机没有 Shizuku 时由本端用 adb.exe 部署 VD server。
        service.onVdDeployRequired = { response -> deployVdServer(response) }

        val dumper = FrameDumper(log)
        val pipeline = VideoDecodePipeline(
            hwaccel = if (hwaccel) VideoDecodePipeline.HWACCEL_D3D11VA else null,
        ).apply {
            onLog = { log.info("video", it) }
            onImage = { image ->
                videoView.setFrame(image)
                dumper.maybeDump(image, framesDecoded.get())
            }
            // WIN-07：码流持续黑屏（编码器卡死但 TCP 仍通）时升级到自动重连。
            onSustainedBlackScreen = { onSustainedBlackScreen() }
            // D-M3：解码器连续重建到上限仍出不了画面（坏 CONFIG 重放等）时升级，
            // 否则循环会以 ~2 次/秒无限转，唯一症状是统计行 rebuilds= 增长。
            onDecodeStalled = { onDecodeStalled() }
            // D-M1：解码线程被抛弃 = native 解码器泄漏。这里只大声记 WARN（
            // SessionStatsLogger 的统计行也带上它），让"泄漏"有可观测的信号。
            onDecoderAbandoned = { log.warn(TAG, "解码线程被抛弃（native 解码器无法回收）—— 见 decoder 日志") }
        }
        service.onVideoFrame = { pipeline.feed(it) }
        pipeline.start()

        // 每秒一条会话统计（SRP-12：循环本体在 SessionStatsLogger）。
        SessionStatsLogger(log, pipeline) { hardwareFlow.value = it }.start(scope)

        val currentGeneration = ++generation
        val session = Session(
            generation = currentGeneration,
            videoView = videoView,
            apps = catalog.apps,
            sessionEnded = sessionEnded,
            state = stateFlow,
            hardwareDecode = hardwareFlow,
            lastError = errorFlow,
            input = service.inputSender,
        )

        // D-L1：先赋值再 launch。反过来（先 launch 后赋值）的话协程体里的代际守卫
        // `_session.value === session` 可能读到**上一代**（早先的窗口关闭用例就
        // 因此丢过 sessionEnded 信号：窗口不自动关）。
        this.scope = scope
        this.service = service
        this.pipeline = pipeline
        _session.value = session

        scope.launch {
            log.info("session", "连接 ${config.phoneHost}:${config.controlPort} ...")
            service.runSession()
            log.info("session", "已结束（解码帧总数=${pipeline.framesDecoded.get()}）")
            keepAwake.disable()
            // 代际守卫（audit WIN-04 / D-03）：只有**仍未被下一代顶替**的会话才
            // 收掉 adb 并把"结束"发给窗口。「应用并重连」会先结束旧代、再建新代，
            // 旧代那个 true 落在 UI 还在收集它的时候就会把窗口关掉退出 —— 正常
            // 断连（_session 仍是这一代）才该关窗。
            //
            // 启动失败豁免（2026-10-09 真机）：**从未到过 STREAMING** 的会话断开
            // 不关窗。否则"双击 exe 时手机端 app 还没启动" → Connection refused
            // → 3 秒即整窗退出，用户视角就是"闪退"（实测 desktop.log 09:23:37
            // 全程）。窗口留着停在 DISCONNECTED，用户开好手机端后点「应用并重连」。
            if (_session.value === session) {
                // D-03：正常断连（手机侧 DISCONNECT / EOF）也必须关掉 adbDeployer
                // —— 桌面端持有的 `adb shell` 进程就是设备侧引擎的存活锚点，不关
                // 则引擎与 adb.exe 一直活到用户关窗或「应用并重连」（VirtualDisplay
                // 泄漏）。此前只有 openSession/stop 调 closeSession() 才走到这里。
                runCatching { adbDeployer.close() }
                if (reachedStreaming.get()) sessionEnded.value = true
            }
        }
    }

    private fun closeSession() {
        if (service == null && pipeline == null && scope == null) return
        // 先摘掉"当前代"（audit WIN-04）：UI 立刻停止收集旧代的 sessionEnded，
        // 与协程侧的代际守卫一起把误关窗的窗口压到最小。
        _session.value = null
        // 顺序：先停会话（手机侧据此回收 VD），再停解码，然后**让设备侧引擎优雅
        // 退出**，最后收掉 adb 与协程域。
        runCatching { service?.stop() }
        runCatching { pipeline?.stop() }
        // 设备侧引擎收尾（2026-10-10，MI 9 实测）：
        // 本地 adb shell 进程**就是**引擎的存活锚点（见 AdbDeployer/AdbRunner），
        // 直接 close()（杀锚点）等于强杀 —— `PipelineServer.cleanup()` 被截断，手机
        // 留在"面板熄灭 + screen_off_timeout 停在 2147483647 哨兵"。所以先发哨兵
        // 等它退干净，再放锚点。
        //
        // runBlocking 在这里是有意的：closeSession 跑在 lifecycle 单线程上、没有
        // 挂起上下文，而这两条路径（关窗 / 应用并重连）都必须**同步**等完收尾
        // （DesktopMain 紧接着 exitProcess；重连则马上要部署新引擎）。等待有界：
        // vdStopEngine = 哨兵 + 有界轮询（graceful 预算 6s）+ -9 兜底（见
        // AdbDeployer.TEARDOWN_GRACEFUL_TIMEOUT_MS）；未部署过引擎时立即返回。
        runCatching { runBlocking { adbDeployer.gracefulStop { log.info("adb", it) } } }
        runCatching { adbDeployer.close() }
        runCatching { scope?.cancel() }
        runCatching { keepAwake.disable() }
        service = null
        pipeline = null
        scope = null
    }

    /**
     * Phase 5c：手机侧没有 Shizuku，VD server 转由本端部署。
     *
     * `dev_mode` 是关键闸门：没有它就不该悄悄调用户的 adb（可能连到别的设备上）。
     *
     * 部署失败必须**抛出去终止会话**（audit WIN-06）：手机侧只在成功时回
     * `VD_PORTS_BOUND`，失败没有任何回报 —— 悄悄 return 会让会话与 UI 一起
     * 停在"正在连接 …"，看不出是在重试还是已经死了。
     */
    private suspend fun deployVdServer(response: HandshakeResponse) {
        if (!settings.devMode) {
            log.warn(
                TAG,
                "手机无 Shizuku（method=${response.connectionMethod}）且未开启 dev_mode —— " +
                    "无法部署 VD server。请在 ${configFile.name} 里设 \"dev_mode\": true",
            )
            throw IOException("未开启 dev_mode，无法部署 VD server（无 Shizuku）")
        }
        // 对端可控字段的白名单校验（audit D-01，CRITICAL）：adbPort 与
        // vdServerJarPath 都来自握手响应，会原样进入 `adb -s host:port shell
        // "CLASSPATH=... exec app_process ..."`。不校验 = 被 MITM / 占位的 LAN
        // 主机拿到 shell 身份的任意代码执行。两者都不做"夹紧后继续"的静默兜底 ——
        // 抛出去终止会话，让用户在日志里看到真实原因。
        AdbDeploy.checkAdbPort(response.adbPort)
        val jarPath = AdbDeploy.sanitizeJarPath(response.vdServerJarPath)
        // vdWidth/vdHeight 的钳制不在本方法里：AdbDeploy.plan 对 phoneVd*>0 的
        // 输入统一走 clampVdDimension（evenMin2 + coerceIn(2,4096)），与 D-M4
        // 共用一条实现；0/负值在那里退回视口尺寸。
        val deployed = adbDeployer.deploy(
            config = config,
            adbPort = response.adbPort,
            jarPath = jarPath,
            log = { log.info("adb", it) },
            phoneVdWidth = response.vdWidth,
            phoneVdHeight = response.vdHeight,
        )
        if (!deployed) {
            throw IOException(
                "ADB 部署 VD server 失败（详见上面的 adb 日志）—— " +
                    "请确认手机已开「无线调试」、与 PC 同网段，且 PC 上有 adb.exe",
            )
        }
    }

    private fun applyKeepAwake() {
        if (_keepAwakeOn.value && service?.state == SessionState.STREAMING) {
            if (!keepAwake.enable()) log.warn(TAG, "本机不支持阻止息屏（非 Windows 或缺少内核库）")
        } else {
            keepAwake.disable()
        }
    }

    /**
     * 持续黑屏的升级动作（audit WIN-07）。
     *
     * 判据在 [VideoDecodePipeline]（与车机端共用 protocol-core 的 `BlackScreenDetector`）；
     * 这里决定"怎么办"：**重新建一代会话**。`restart()` 会重新握手，而手机侧
     * `handleHandshake` 每次都会拆掉旧 VirtualDisplay 再建新的 —— 这正是编码器/VD
     * 卡死真正需要的那一步，桌面端自己没有重建 VD 的能力（只管收流）。
     *
     * 为什么要全局预算：手机**合法地**停在暗色界面（或全黑画面）同样会持续输出极小
     * I 帧，而检测器的"只升级一次"闩锁是**按会话**重置的 —— 只靠它就会变成重连风暴。
     * 预算用完后只记日志，把决定权交回用户（「显示」页的"应用并重连"）。
     */
    private fun onSustainedBlackScreen() {
        val attempt = blackScreenReconnects.incrementAndGet()
        if (attempt > MAX_BLACK_SCREEN_RECONNECTS) {
            log.warn(
                TAG,
                "疑似持续黑屏：本进程内已自动重连 $MAX_BLACK_SCREEN_RECONNECTS 次，不再自动重试 —— " +
                    "若画面确实卡住，请在「显示」页点「应用并重连」",
            )
            return
        }
        log.warn(TAG, "疑似持续黑屏：自动重连（第 $attempt/$MAX_BLACK_SCREEN_RECONNECTS 次）")
        restart(dpiOverride = null, hwaccelEnabled = null)
    }

    /**
     * 解码器连续重建到上限仍无画面（audit D-M3）。
     *
     * 升级动作与持续黑屏相同（重建一代会话，让手机侧重新起编码器），但共用同一份
     * 预算：两者都是"这一代已经没救了"，让它们各自重连只会把窗口变成重连风暴。
     * 预算用尽后只记日志 —— 已经大声告诉用户去哪手动重连了。
     */
    private fun onDecodeStalled() {
        val attempt = blackScreenReconnects.incrementAndGet()
        if (attempt > MAX_BLACK_SCREEN_RECONNECTS) {
            log.warn(
                TAG,
                "解码器反复重建仍无画面：本进程内已自动重连 $MAX_BLACK_SCREEN_RECONNECTS 次，" +
                    "不再自动重试 —— 请在「显示」页点「应用并重连」",
            )
            return
        }
        log.warn(TAG, "解码器反复重建仍无画面：自动重连（第 $attempt/$MAX_BLACK_SCREEN_RECONNECTS 次）")
        restart(dpiOverride = null, hwaccelEnabled = null)
    }

    /**
     * 在 Swing EDT 上同步执行 [block] 并返回其结果（audit D-L5）。
     *
     * [openSession] 跑在 lifecycle 线程上，而 [SwingVideoView] 的构造（设 opaque、
     * 加鼠标监听、建 Graphics）受 Swing 的单线程规则约束，必须在 EDT 上做。
     * `invokeAndWait` 返回 void，结果经 [holder] 取回。
     *
     * 异常兜底：EDT 不可用 / 被打断时退回直接构造 —— 没有真实 EDT 也就没有线程
     * 规则可违反（block 自身抛错时这里会再抛一次同样的异常，语义不变）。
     */
    private fun <T> runOnEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val holder = java.util.concurrent.atomic.AtomicReference<T>()
        try {
            SwingUtilities.invokeAndWait { holder.set(block()) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return block()
        } catch (_: java.lang.reflect.InvocationTargetException) {
            return block()
        }
        return holder.get()
    }

    private companion object {
        const val TAG = "app"

        /**
         * [stop] 等待收尾的上限（audit WIN-03）。
         *
         * 预算（2026-10-10 实测修订）：解码线程 join 最坏 2s + 设备侧引擎优雅退出
         * （`AdbDeployer.TEARDOWN_GRACEFUL_TIMEOUT_MS` 12s）+ -9 兜底 3s + 每条
         * shell 命令的往返（~0.5s）≈ 17.5s，取 20s。
         *
         * 此前 5s 只覆盖"解码 join + 杀 adb 进程"；现在 closeSession 还会**等引擎
         * 把 cleanup 跑完**（实测 6.1~6.3s），否则锚点一杀 cleanup 就被截断
         * （面板熄、screen_off_timeout 停在哨兵值）。超时不会中断收尾本身，只是
         * 本方法不再等 —— 而调用方紧接着就 `exitProcess`，所以必须留足。
         */
        const val STOP_TIMEOUT_MS = 20_000L

        /** 本进程内允许自动发起的"持续黑屏重连"次数（audit WIN-07）。 */
        const val MAX_BLACK_SCREEN_RECONNECTS = 2
    }
}
