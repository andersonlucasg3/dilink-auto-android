package com.dilinkauto.server.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.view.Surface
import com.dilinkauto.protocol.*
import com.dilinkauto.server.CarCrashHandler
import com.dilinkauto.server.CarCrashReport
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.adb.RemoteAdbController
import com.dilinkauto.server.decoder.AudioPlayer
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 车机端核心前台 Service 与依赖注入根（SRP：纯粹作为宿主与协调中心）。
 *
 * 职责分工：
 *  - [CarSessionCoordinator]：多通道（9636/9638/9639/9640）网络连接生命周期、握手与视口/黑屏自愈重握手状态机；
 *  - [CarAdbTracker]：USB ADB 与 TCP ADB（Dev Mode）设备发现、权限请求与连接建立；
 *  - [CarAppListReceiver]：应用列表解析、单图标/聚合图标内存预算安全门控与持久化解码缓存；
 *  - [CarConnectionService]：Android Service 生命周期承接、通知与 WakeLock 管理、车载传感器/提供者装配。
 */
class CarConnectionService : Service() {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val videoDecoder = VideoDecoder()
    val audioPlayer = AudioPlayer()

    internal val carPrefs: CarPrefs by lazy { CarPrefs(this) }
    internal val vdDeployer: VdServerDeployer by lazy {
        VdServerDeployer(
            resources = resources,
            scope = scope,
            carPrefs = carPrefs,
            adbTrackerProvider = { adbTracker },
            sessionCoordinatorProvider = { sessionCoordinator },
            setStatusMessage = { resId, args -> setStatusMessage(resId, *args) },
            logSink = { msg, lvl -> carLogSend(msg, lvl) },
        )
    }

    internal val appListReceiver: CarAppListReceiver by lazy {
        CarAppListReceiver(this) { msg, lvl -> carLogSend(msg, lvl) }
    }

    internal val adbTracker: CarAdbTracker by lazy {
        CarAdbTracker(
            context = this,
            carPrefs = carPrefs,
            scope = scope,
            log = { msg, lvl -> carLogSend(msg, lvl) },
            setStatusMessage = { resId, args -> setStatusMessage(resId, *args) },
            getPhoneHost = { sessionCoordinator.phoneHost },
            isHandshakeDone = { sessionCoordinator.handshakeDone },
            isVdServerStarted = { sessionCoordinator.vdServerStarted },
            deployVdServer = { vdDeployer.deploy() },
            deployVdServerDirect = { vdDeployer.deployDirect(it) },
            onAdbReady = {
                if (sessionCoordinator.state.value == State.IDLE) {
                    // issue #201：ADB 就绪是自动入口，交给 startConnection 的单一闸门判定；
                    // 用户断开后这里会被挡下并打 `[Connect] auto-reconnect suppressed`。
                    sessionCoordinator.startConnection(ConnectTrigger.AUTO_ADB_READY)
                } else {
                    sessionCoordinator.checkAndAdvance()
                }
            },
            onUsbDetachedMidSession = {
                if (sessionCoordinator.state.value == State.STREAMING || sessionCoordinator.state.value == State.CONNECTED) {
                    carLogSend("USB ADB detached mid-session — ending session", "W")
                    scope.launch { sessionCoordinator.handleDisconnect() }
                }
            },
            // issue #201：USB attach / 权限回调 / TCP ADB 重拨统一问同一个闸门。
            autoReconnectAllowed = { trigger -> sessionCoordinator.allowReconnect(trigger) },
        )
    }

    private val touchSender: CarTouchSender by lazy {
        CarTouchSender(
            inputConnectionProvider = { sessionCoordinator.inputConnection },
            stateProvider = { sessionCoordinator.state.value },
            log = { msg, level -> carLogSend(msg, level) }
        )
    }

    private val logWriter: CarLogWriter by lazy {
        CarLogWriter(TAG) { sessionCoordinator.controlConnection }
    }

    internal val sessionCoordinator: CarSessionCoordinator by lazy {
        CarSessionCoordinator(
            context = this,
            scope = scope,
            carPrefs = carPrefs,
            videoDecoder = videoDecoder,
            audioPlayer = audioPlayer,
            touchSender = touchSender,
            logWriter = logWriter,
            appListReceiver = appListReceiver,
            adbTrackerProvider = { adbTracker },
            deployVdServer = { vdDeployer.deploy() },
            updateNotification = { updateNotification(it) },
            // issue #151：手机电量状态。null = 会话结束，让 chip 回到 `—`。
            onPhoneStatus = { status ->
                if (status == null) phoneStatusProvider?.clear() else phoneStatusProvider?.onStatus(status)
            },
        )
    }

    private lateinit var notifier: ForegroundNotifier
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ─── Observable StateFlows (Delegated to sub-components) ───
    val state: StateFlow<State> get() = sessionCoordinator.state
    val phoneName: StateFlow<String> get() = sessionCoordinator.phoneName
    val phoneAudioSupported: StateFlow<Boolean> get() = sessionCoordinator.phoneAudioSupported
    val appList: StateFlow<List<AppInfo>> get() = appListReceiver.appList
    val mediaMetadata: StateFlow<MediaMetadata?> get() = sessionCoordinator.mediaMetadata
    val playbackState: StateFlow<PlaybackState?> get() = sessionCoordinator.playbackState
    val vdStackEmpty: SharedFlow<Unit> get() = sessionCoordinator.vdStackEmpty
    val videoReady: StateFlow<Boolean> get() = sessionCoordinator.videoReady
    /** issue #203：解码器实测入帧帧率（0 = 无样本），设置页展示用。 */
    val measuredFps: StateFlow<Double> get() = sessionCoordinator.measuredFps
    /** issue #202：手机端回传的实际生效 VD 密度 / 对应视口上限（0 = 尚未握手）。 */
    val effectiveVdDpi: StateFlow<Int> get() = sessionCoordinator.effectiveVdDpi
    val effectiveVdSafeMaxDpi: StateFlow<Int> get() = sessionCoordinator.effectiveVdSafeMaxDpi
    val statusMessage: StateFlow<String> get() = sessionCoordinator.statusMessage
    val appInfoData: StateFlow<AppInfoDataMessage?> get() = appListReceiver.appInfoData

    private val _timeState = MutableStateFlow<TimeUiState?>(null)
    val timeState: StateFlow<TimeUiState?> = _timeState.asStateFlow()
    private var clockProvider: ClockProvider? = null

    private val _altitudeState = MutableStateFlow<AltitudeUiState?>(null)
    val altitudeState: StateFlow<AltitudeUiState?> = _altitudeState.asStateFlow()
    private var altitudeProvider: AltitudeProvider? = null

    private val _weatherState = MutableStateFlow<WeatherUiState?>(null)
    val weatherState: StateFlow<WeatherUiState?> = _weatherState.asStateFlow()
    private var weatherProvider: WeatherProvider? = null

    /**
     * 手机电量（issue #151）：由 [PhoneStatusProvider] 持有，数据来自
     * [CarSessionCoordinator] 解出的 `DataMsg.PHONE_STATUS`，断开时清回 null（chip 显示 `—`）。
     */
    private val _phoneStatusState = MutableStateFlow<PhoneStatusUiState?>(null)
    val phoneStatusState: StateFlow<PhoneStatusUiState?> = _phoneStatusState.asStateFlow()
    private var phoneStatusProvider: PhoneStatusProvider? = null

    enum class State { IDLE, CONNECTING, CONNECTED, STREAMING }

    inner class LocalBinder : Binder() {
        val service: CarConnectionService get() = this@CarConnectionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ─── External UI Facade Methods ───
    fun setStartupFps(value: Int) = sessionCoordinator.setStartupFps(value)
    fun connectManual(host: String, port: Int = Ports.DEFAULT_PORT) = sessionCoordinator.connectManual(host, port)
    fun onUsbDeviceFromActivity(device: UsbDevice) = adbTracker.onUsbDeviceFromActivity(device)

    fun onMirrorSurfaceCreated(surface: Surface) = sessionCoordinator.onMirrorSurfaceCreated(surface)
    fun onMirrorSurfaceDestroyed() = sessionCoordinator.onMirrorSurfaceDestroyed()
    fun sendTouchEvent(event: TouchEvent) = touchSender.sendTouchEvent(event)
    fun sendTouchBatch(pointers: List<TouchEvent>) = touchSender.sendTouchBatch(pointers)
    fun launchApp(packageName: String) = sessionCoordinator.launchApp(packageName)
    fun goRecent() = sessionCoordinator.goRecent()
    fun goHome() = sessionCoordinator.goHome()
    fun goBack() = sessionCoordinator.goBack()
    fun onCarViewportChanged(widthPx: Int, heightPx: Int, dpi: Int) = sessionCoordinator.onCarViewportChanged(widthPx, heightPx, dpi)
    fun requestUninstall(packageName: String) = sessionCoordinator.requestUninstall(packageName)
    fun requestAppInfo(packageName: String) = sessionCoordinator.requestAppInfo(packageName)
    fun clearAppInfoData() = appListReceiver.clearAppInfoData()
    fun onLocationPermissionGranted() = altitudeProvider?.onPermissionResult()
    fun disconnectFromPhone() = sessionCoordinator.disconnectFromPhone()

    /** 用户点「重新连接」（issue #183）：从断开态重新发起发现/连接。 */
    fun reconnect() = sessionCoordinator.reconnect()
    fun sendMediaAction(action: MediaAction) = sessionCoordinator.sendMediaAction(action)
    internal fun carLogSend(msg: String, level: String = "I") = logWriter.send(msg, level)
    internal fun setStatusMessage(resId: Int, vararg formatArgs: Any) = sessionCoordinator.setStatusMessage(resId, *formatArgs)

    internal fun sanitizePeerDimension(value: Int, fallback: Int): Int =
        CarSessionCoordinator.sanitizePeerDimension(value, fallback)

    // ─── Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        notifier = ForegroundNotifier(
            context = this,
            channelId = ServerApp.CHANNEL_SERVICE,
            notificationId = NOTIFICATION_ID,
            wakeLockTag = "DiLinkAuto::CarConnectionService",
            title = getString(R.string.notification_title),
            text = { ctx, res -> ctx.getString(res) }
        )
        sessionCoordinator.targetFps = carPrefs.startupFps
        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)
        logWriter.start()
        videoDecoder.logSink = { msg -> carLogSend(msg) }
        audioPlayer.logSink = { msg -> carLogSend(msg) }
        // issue #200：把控制连接的静默时长接进解码器。GAP / STALLED 行带上它就能区分
        // 「链路断」（控制帧一起停，现场 07.29→16.01 断了 ~8.7s）与「编码停」（控制帧
        // 照常、只有视频不动）。只读快照，不参与任何超时判定。
        videoDecoder.msSinceControlFrame = {
            sessionCoordinator.controlConnection?.msSinceLastFrameReceived() ?: -1L
        }
        // issue #200 评审：LINK GAP / STALLED 是实车回签要 grep 的证据，release 下
        // CarLogWriter 默认关闭（BuildConfig.DEBUG=false），必须走 sendUnconditional
        // 才能保证车上抓得到（接线方式同 #191/#197 的 AudioPlayer.diagSink）。
        videoDecoder.diagSink = { msg -> logWriter.sendUnconditional(msg) }
        // issue #181：播放线程因 CONFIG 永不到达 / 解码器创建失败等原因退出时，用
        // sendUnconditional 上报（release 下 send 会被 enabled=false 丢掉），保证
        // logcat 与手机侧 client.log 都能 grep 到 `[AudioPlayer][E]`。
        audioPlayer.onFatal = { reason ->
            logWriter.sendUnconditional("[AudioPlayer][E] $reason", "E")
        }
        // issue #194：AudioDiag 周期诊断行必须绕过 enabled（release 下默认 false），
        // 否则正式包上车后 `grep AudioDiag` 什么都抓不到。
        audioPlayer.diagSink = { msg -> logWriter.sendUnconditional(msg) }

        // issue #9 / #75 / #163 / #198：两端共用 protocol-core 的 SyncRequestGate（滑动窗口：
        // 1s 内最多 2 次）。IDR 请求统一走 sessionCoordinator.requestSyncFrame —— 参考链断裂
        // 与「Surface 重绑失败后重建解码器」共用同一闸门，任何一方都不能绕过限流。
        videoDecoder.onNeedsSyncFrame = {
            sessionCoordinator.requestSyncFrame("reference chain broken")
        }

        // issue #200：首帧真正上屏后就绪判定必须能被触发一次。喂帧线程只在持有 codecLock
        // 时回调，协调器内部只做轻量投递；这样「首帧渲染后码流停住」也不会卡在等待遮罩。
        videoDecoder.onFirstFrameOnSurface = {
            sessionCoordinator.onFirstFrameRenderedOnSurface()
        }

        // issue #203：实测入帧帧率（每 30 帧一次，墙钟口径）推给设置页展示。
        videoDecoder.onMeasuredFps = { fps -> sessionCoordinator.onMeasuredFps(fps) }

        videoDecoder.onContentBlackPersistent = {
            carLogSend("[BLACK] decode alive but content still black — requesting phone-side display heal", "W")
            sessionCoordinator.sendCommandToVd(ControlMsg.REQUEST_DISPLAY_HEAL)
        }

        videoDecoder.onSustainedBlackScreen = {
            sessionCoordinator.handleSustainedBlackScreen()
        }

        CarCrashHandler.logSink = { msg -> carLogSend(msg) }
        carLogSend(CarCrashReport.deviceInfo(this))

        val crash = CarCrashHandler.consumePendingCrash()
        if (crash != null) {
            carLogSend("──── PREVIOUS CRASH REPORT ────", "E")
            crash.lines().forEach { carLogSend(it, "E") }
            carLogSend("──── END CRASH REPORT ────", "E")
        }

        acquireWakeLock()
        registerNetworkCallback()
        adbTracker.registerUsbReceiver()

        ClockProvider(scope, this, _timeState).let {
            clockProvider = it
            it.start()
        }
        AltitudeProvider(scope, this, _altitudeState, log = { carLogSend(it) }).let {
            altitudeProvider = it
            it.start()
        }
        WeatherProvider(scope, this, carPrefs, _weatherState).let {
            weatherProvider = it
            it.start()
        }
        // issue #151：手机电量的唯一写入者；数据由 sessionCoordinator 的
        // DataMsg.PHONE_STATUS 分支回调进来。
        PhoneStatusProvider(_phoneStatusState).let {
            phoneStatusProvider = it
            it.start()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                carLogSend("WiFi network available — retrying WiFi track if needed")
                // issue #201：网络恢复同样是自动入口，先过闸门（用户断开后不再重开探测）。
                // TODO(#204)：available 不恢复是另一条独立缺陷，这里只加抑制，不改恢复语义。
                if (!sessionCoordinator.allowReconnect(ConnectTrigger.AUTO_NETWORK_AVAILABLE)) return
                if (sessionCoordinator.state.value == State.CONNECTING && !sessionCoordinator.wifiReady) {
                    sessionCoordinator.startWifiTrack()
                }
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                // issue #201：MainActivity.onCreate 每次重建都会发 ACTION_START，属于自动入口；
                // 用户断开后如果这里还无条件 startConnection，系统重建 Activity 就等于自动重连。
                sessionCoordinator.startConnection(ConnectTrigger.AUTO_SERVICE_START)
            }
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                val host = intent.getStringExtra(EXTRA_HOST)?.trim()
                val port = intent.getIntExtra(EXTRA_PORT, Ports.DEFAULT_PORT)
                if (host.isNullOrEmpty()) {
                    carLogSend("ACTION_CONNECT rejected: blank host", "W")
                    sessionCoordinator.setStatusMessage(R.string.status_blank_host)
                    return START_STICKY
                }
                if (port !in 1..65535) {
                    carLogSend("ACTION_CONNECT rejected: port $port out of range", "W")
                    sessionCoordinator.setStatusMessage(R.string.status_bad_port)
                    return START_STICKY
                }
                // 显式携带 host 的 CONNECT 属于用户操作（clearUserDisconnected = true 由
                // ConnectTrigger.USER_EXPLICIT_CONNECT 决定，不再在这里裸写标志）。
                sessionCoordinator.beginConnect(host, port, ConnectTrigger.USER_EXPLICIT_CONNECT)
            }
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        sessionCoordinator.shutdown()
        adbTracker.destroy()
        logWriter.shutdown()
        scope.cancel()
    }

    private fun acquireWakeLock() = notifier.acquireWakeLock()
    private fun buildNotification(messageRes: Int): Notification = notifier.buildNotification(messageRes)
    private fun updateNotification(messageRes: Int) = notifier.updateNotification(messageRes)

    override fun onDestroy() {
        shutdown()
        touchSender.shutdown()
        clockProvider?.stop()
        clockProvider = null
        altitudeProvider?.stop()
        altitudeProvider = null
        weatherProvider?.stop()
        weatherProvider = null
        phoneStatusProvider?.stop()
        phoneStatusProvider = null
        networkCallback?.let {
            try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
        networkCallback = null
        notifier.releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarConnectionService"
        const val ACTION_START = "com.dilinkauto.server.START"
        const val ACTION_CONNECT = "com.dilinkauto.server.CONNECT"
        const val ACTION_STOP = "com.dilinkauto.server.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val NOTIFICATION_ID = 2001

        internal const val MAX_PEER_DIMENSION = 4096
        internal const val MIN_PEER_DIMENSION = 2

        /**
         * 单张图标解码前的宽高上限（audit S-M5, D-M5 对齐）。
         */
        const val MAX_ICON_DIM = CarAppListReceiver.MAX_ICON_DIM

        /**
         * 单张图标的原始 PNG 字节上限（1MB，audit S-M5, D-M5 对齐）。
         */
        const val MAX_ICON_BYTES = CarAppListReceiver.MAX_ICON_BYTES

        @Deprecated("Use MAX_ICON_DIM", ReplaceWith("MAX_ICON_DIM"))
        internal const val MAX_ICON_DIMENSION = MAX_ICON_DIM

        @Deprecated("Use MAX_ICON_BYTES", ReplaceWith("MAX_ICON_BYTES"))
        internal const val MAX_ICON_PNG_BYTES = MAX_ICON_BYTES

        /**
         * 单个 APP_LIST 消息的聚合图标字节预算（64MB，audit S-M5）。
         */
        const val MAX_APP_LIST_ICON_BYTES = CarAppListReceiver.MAX_APP_LIST_ICON_BYTES

        internal fun reconnectBackoffMs(consecutiveFailures: Int): Long =
            BackoffPolicy.DEFAULT.compute(consecutiveFailures)

        internal fun iconBudgetAccepts(totalBytes: Long, nextIconBytes: Int): Boolean =
            CarAppListReceiver.iconBudgetAccepts(totalBytes, nextIconBytes)

        internal const val MAX_BLACK_SCREEN_REBUILDS = 2
        internal const val VIEWPORT_REHANDSHAKE_DEBOUNCE_MS = CarSessionCoordinator.VIEWPORT_REHANDSHAKE_DEBOUNCE_MS
    }
}
