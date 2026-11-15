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
import android.os.SystemClock
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
                    sessionCoordinator.startConnection()
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

        // issue #9 / #75 / #163：两端共用 protocol-core 的 SyncRequestGate（滑动窗口：1s 内最多 2 次）
        videoDecoder.onNeedsSyncFrame = {
            val now = SystemClock.elapsedRealtime()
            var granted = false
            while (true) {
                val current = sessionCoordinator.syncRequestGate.get()
                // 窗口已满：拒绝；放行则 CAS 换上新状态，CAS 失败说明有并发请求，重读再判。
                val next = current.tryAcquire(now) ?: break
                if (sessionCoordinator.syncRequestGate.compareAndSet(current, next)) {
                    granted = true
                    break
                }
            }
            if (granted) {
                carLogSend("[VideoDecoder] requesting IDR from phone (reference chain broken)")
                sessionCoordinator.sendCommandToVd(ControlMsg.REQUEST_SYNC_FRAME)
            } else {
                val lastGrant = sessionCoordinator.syncRequestGate.get().grantsMs.lastOrNull()
                carLogSend(
                    "[VideoDecoder] IDR request throttled (window ${VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS}ms, " +
                        "max ${VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW} grants, " +
                        "last grant ${lastGrant?.let { now - it } ?: -1L}ms ago)"
                )
            }
        }

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
                sessionCoordinator.startConnection()
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
                carPrefs.userDisconnected = false
                sessionCoordinator.beginConnect(host, port)
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
