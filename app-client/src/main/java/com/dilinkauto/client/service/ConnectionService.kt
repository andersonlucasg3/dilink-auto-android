package com.dilinkauto.client.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.display.VirtualDisplayClient
import com.dilinkauto.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

typealias State = SessionState

class ConnectionService : Service() {

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var controlConnection: Connection? = null
    @Volatile private var vdClient: VirtualDisplayClient? = null
    private var pendingAppLaunch: String? = null
    private var handshakeJob: Job? = null

    /**
     * issue #76：本会话是否收到过 `HANDSHAKE_REQUEST`。
     *
     * 只用于两件事：握手超时时区分「对端一直不发」与「收到了但本端处理慢」，
     * 以及空会话 cleanup 时跳过整条物理屏恢复链路。每次 accept 新会话时重置。
     */
    @Volatile
    private var handshakeReceived = false
    private var targetFps = VideoConfig.TARGET_FPS
    private var targetBitrate = VideoConfig.DEFAULT_BITRATE
    private var serviceRegistration: Discovery.ServiceRegistration? = null
    private var connectionLoopJob: Job? = null
    private var networkMonitor: ClientNetworkMonitor? = null
    private lateinit var appListBuilder: AppListBuilder
    private lateinit var displayRestorer: PhoneDisplayRestorer

    // ─── Session generation (audit A-01) ───
    //
    // cleanupSession() used to be guarded by a single AtomicBoolean that the
    // network callbacks reset *before* the cancelled loop's finally block had
    // run, so one generation's late teardown consumed the guard belonging to
    // the *next* generation: the fresh lifecycle ServerSocket was never closed
    // and the stale vdClient kept streaming (the leaked-VD failure mode).
    //
    // Every accept now mints a generation id; a teardown only runs when the id
    // it captured is still the active one, so a stale finally block is a no-op
    // and the new session's teardown always gets its own guard.
    private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var activeSessionId = 0

    /** Serialises the vdClient read-modify-write in handleHandshake (audit A-03). */
    private val vdLock = Any()

    /**
     * Process-lifetime scope for teardown work (audit A-04).
     *
     * `cleanupSession()` runs on Main from several call sites and
     * `VirtualDisplayClient.stopVdServer()` joins a write worker for up to
     * 1s — an ANR-shaped stall. Moving the blocking part here (instead of
     * serviceScope, which onDestroy() cancels before the work runs, and
     * instead of Main) keeps the physical-panel restore alive after the
     * service is stopped. Mirrors PhoneDisplayRestorer's own scope contract.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 内嵌资产（vd-server.jar / app-server.apk）的部署器。
     *
     * **必须是 `by lazy`**：[deployAssets] 在 onCreate 早期就把它交给工作协程
     * （`serviceScope.launch(Dispatchers.IO)`），而 onCreate 的主线程路径还要跑完
     * 设备信息、IME 缓存等 IO 才轮到赋值 —— `lateinit` 版本在真机上被 worker
     * 线程抢先访问，服务崩溃重启（2026-10-09 实测）。与 [notifier] 是同一次
     * DRY 重构留下的同族问题，修法保持一致。
     */
    private val assetDeployer: AssetDeployer by lazy { AssetDeployer(AssetManagerAssetSource(applicationContext.assets)) }

    /**
     * 前台通知 + wake lock 门面（DRY-6，与车机端共享）。
     *
     * **必须是 `by lazy`，不能是 `lateinit`**：`onCreate` 在初始化其余字段之前就会调
     * [acquireWakeLock]（真机实测，2026-10-09），`lateinit` 版本会让服务一创建即抛
     * `UninitializedPropertyAccessException`，AMS 随后把重启排到 30 分钟后 —— 表现为
     * "装上后毫无反应"。lazy 把构造时机绑定到首次访问，访问顺序不再是隐式契约。
     * 构造块只捕获参数、不做 Android API 调用（见 [ForegroundNotifier]），
     * 首次访问时 Service 必已 attach，`getString` 可用。
     */
    private val notifier: com.dilinkauto.protocol.ForegroundNotifier by lazy {
        // Stop action 是手机侧独有的；车机端没有面向用户的停止入口。
        ForegroundNotifier(
            context = this,
            channelId = ClientApp.CHANNEL_SERVICE,
            notificationId = NOTIFICATION_ID,
            wakeLockTag = "DiLinkAuto::ConnectionService",
            title = getString(R.string.notification_title),
            text = { ctx, res -> ctx.getString(res) },
            extraAction = ForegroundNotifier.stopAction(
                iconRes = android.R.drawable.ic_media_pause,
                label = getString(R.string.notification_action_stop),
                serviceClass = ConnectionService::class.java,
                action = ACTION_STOP
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var packageRemovedReceiver: BroadcastReceiver? = null

    /**
     * 手机电量采集与上行（issue #151）。
     *
     * sticky 广播只在服务存活期间订阅；`isReady` 要求**握手完成**且控制连接可用，
     * 以免把新消息推给尚未完成握手的对端（老车机对它也是静默忽略，这里只是双保险）。
     */
    private var phoneBatteryReporter: PhoneBatteryReporter? = null
    @Volatile private var savedDefaultIme: String? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.loadEnabled(getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE))
        FileLog.rotate() // Archive previous log, start fresh
        // Clear stale static state from previous service instance
        activeConnection = null
        _serviceState.value = State.IDLE
        acquireWakeLock()
        setupNetworkMonitor()
        registerPackageRemovedReceiver()
        // issue #151：电量采集跟随服务生命周期；只在握手完成后上行，未连接不发。
        PhoneBatteryReporter(
            scope = serviceScope,
            context = applicationContext,
            isReady = { handshakeReceived && controlConnection?.isConnected == true },
            send = { type, payload -> controlConnection?.sendData(type, payload) }
        ).also {
            phoneBatteryReporter = it
            it.start()
        }
        deployAssets()
        logDeviceInfo()
        cacheDefaultIme()
        // Wire the extracted locator's WiFi dependency (avoids passing the Service
        // into CarIpLocator; the locator is a plain object for unit-testability).
        @Suppress("DEPRECATION")
        CarIpLocator.wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
        // A-M8: publish the subnet snapshot once here instead of enumerating
        // interfaces per scan; findCarAdb is additionally serialized (Mutex).
        refreshSubnetSnapshot()
        appListBuilder = AppListBuilder(applicationContext, serviceScope)
        // No scope passed: the restorer owns a process-lifetime scope so a
        // Service.onDestroy() mid-restore cannot skip `cmd display power-reset`.
        displayRestorer = PhoneDisplayRestorer(applicationContext)
    }

    /** Snapshot the device's local IPv4 addresses for CarIpLocator's sweep (audit A-M8). */
    private fun refreshSubnetSnapshot() {
        val localIps = NetUtil.localIpv4Addresses()
        CarIpLocator.setLocalSubnetSnapshot(localIps)
        if (localIps.isNotEmpty()) {
            FileLog.i(TAG, "Local IPv4 snapshot for car scan: ${localIps.joinToString(", ")}")
        }
    }

    private fun cacheDefaultIme() {
        try {
            val currentIme = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            if (com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(currentIme)) {
                savedDefaultIme = currentIme
                getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE).edit().putString(AppPrefs.SAVED_DEFAULT_IME, currentIme).apply()
                FileLog.i(TAG, "Cached default IME: $currentIme")
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to cache default IME: ${e.message}")
        }
    }

    private fun logDeviceInfo() {
        // issue #8: 启动头先打一行可读版本号，不必反编译 dex 即可判断跑的是哪份构建。
        FileLog.i(TAG, "DiLink client ${com.dilinkauto.client.BuildConfig.VERSION_NAME}+g${com.dilinkauto.client.BuildConfig.BUILD_REVISION}")
        FileLog.i(TAG, DeviceInfo.buildDeviceInfoBlock(this))
    }

    private fun registerPackageRemovedReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (replacing) return // ignore updates (app is reinstalled immediately)
                    FileLog.i(TAG, "Package removed: $pkg — notifying car")
                    val conn = controlConnection
                    if (conn != null && conn.isConnected) {
                        try {
                            conn.sendData(DataMsg.APP_UNINSTALLED, pkg.toByteArray(Charsets.UTF_8))
                        } catch (e: Exception) {
                            FileLog.w(TAG, "Failed to send APP_UNINSTALLED: ${e.message}")
                        }
                        // Resend the full app list so car has accurate state
                        appListBuilder.sendAppList(conn)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        packageRemovedReceiver = receiver
    }

    @Volatile
    private var assetsReady = false

    private fun deployAssets() {
        serviceScope.launch(Dispatchers.IO) {
            // DIR_PATH 已是绝对路径（/sdcard/DiLinkAuto），直接使用。
            // 不要再与 getExternalStorageDirectory() 拼接，否则 child 的前导斜杠
            // 不会重置路径，会拼出 /storage/emulated/0/sdcard/DiLinkAuto 影子目录。
            val dir = java.io.File(VdDeploy.DIR_PATH)
            dir.mkdirs()
            assetDeployer.extract(VdDeploy.JAR_NAME, java.io.File(dir, VdDeploy.JAR_NAME))
            assetDeployer.extract("app-server.apk", java.io.File(filesDir, "app-server.apk"))
            assetsReady = true
        }
    }

    private suspend fun ensureAssetsReady() {
        if (assetsReady) return
        val apkFile = java.io.File(filesDir, "app-server.apk")
        repeat(50) {
            if (apkFile.exists()) return
            delay(100)
        }
    }

    /**
     * 确保磁盘上的 vd-server.jar 与当前 APK 内嵌的版本一致，返回该 jar 的 CRC。
     *
     * 为什么必须在每次握手前调用：磁盘上的 jar 会跨进程存活，而
     * [deployAssets] 只在 [onCreate] 跑一次。若这里不校验，Service 未被系统
     * 回收、只是重连车机或强杀重启 vd-server 时，`CLASSPATH=... app_process`
     * 会静默加载上一次的旧引擎 —— 应用能跑、日志正常，但跑的是旧代码。
     *
     * CRC 相同则直接返回，代价仅一次内存读，不写盘、不需要 Shizuku；
     * 因此 Shizuku 与车机 ADB 两条部署路径都可以无条件调用。
     */
    private fun ensureVdServerJarCurrent(): Long {
        val target = java.io.File(VdDeploy.JAR_PATH)
        val crc = assetDeployer.ensureCurrent(VdDeploy.JAR_NAME, target)
        if (crc < 0L) {
            // CRC state unknown (asset unreadable / write failed). The old jar on
            // disk may be stale, so report -1 rather than guessing.
            FileLog.w(TAG, "VD jar CRC unknown; on-disk jar at ${target.absolutePath} may be stale")
        }
        return crc
    }

    private fun setupNetworkMonitor() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = ClientNetworkMonitor(
            connectivityManager = cm,
            scope = serviceScope,
            listener = object : ClientNetworkMonitor.Listener {
                override fun onNetworkAvailable(network: Network) {
                    refreshSubnetSnapshot()
                    val state = _serviceState.value
                    if (state == State.WAITING) {
                        FileLog.i(TAG, "New network while WAITING — restarting listen loop")
                        cleanupSession()
                        connectionLoopJob?.cancel()
                        startConnectionLoop()
                    }
                }

                override fun onNetworkLostConfirmed(network: Network) {
                    resetConnectionForNetworkChange()
                }

                override fun isConnectionActive(): Boolean {
                    val conn = controlConnection
                    return conn != null && conn.isConnected
                }
            }
        ).apply { start() }
    }

    private fun resetConnectionForNetworkChange() {
        val state = _serviceState.value
        when (state) {
            State.WAITING -> {
                FileLog.i(TAG, "Network changed while WAITING — restarting listen loop")
                cleanupSession()
                connectionLoopJob?.cancel()
                startConnectionLoop()
            }
            State.CONNECTED, State.STREAMING -> {
                // Proactive disconnect — don't wait 10s for heartbeat timeout.
                // The connection is likely dead if the network interface changed.
                FileLog.i(TAG, "Network lost while $state — proactive disconnect")
                cleanupSession()
                // listenAndHandleOneConnection() will restart via its finally block
            }
            else -> {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_waiting))
                startConnectionLoop()
            }
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_INSTALL_CAR -> {
                val explicitIp = intent.getStringExtra("car_ip")
                installCarApp(explicitIp)
            }
            ACTION_ALLOWLIST_UPDATED -> {
                // Allowlist screen changed the selection — re-send so the car grid updates live.
                appListBuilder.sendAppList(controlConnection)
            }
            // START_STICKY 的系统重启以 **intent=null** 重新投递 onStartCommand。
            // 此前没有 null 分支：AMS 重启进程后服务再也不会回到监听态（真机
            // 2026-10-09 实测：08:42:12 进程被重启，日志只有设备信息 + 资产部署，
            // 没有 "Listening for car connection"，9637-9639 全部无监听，直到
            // 用户手动重开 app）。行为与 ACTION_START 等价；isActive 防重入。
            null -> {
                FileLog.i(TAG, "onStartCommand with null intent — system restart, resuming service")
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_waiting))
                if (connectionLoopJob?.isActive != true) startConnectionLoop()
            }
        }
        return START_STICKY
    }

    // ─── Connection Loop ───

    private fun startConnectionLoop() {
        connectionLoopJob?.cancel()
        connectionLoopJob = serviceScope.launch {
            // Register mDNS in background — don't block the listen loop.
            // NsdManager may never invoke the callback if there's no active network,
            // which would hang the coroutine indefinitely.
            launch {
                try {
                    if (serviceRegistration == null) {
                        serviceRegistration = withTimeoutOrNull(5000) {
                            Discovery.registerService(
                                this@ConnectionService,
                                port = Ports.DEFAULT_PORT,
                                deviceName = android.os.Build.MODEL
                            )
                        }
                        if (serviceRegistration == null) {
                            FileLog.w(TAG, "mDNS registration timed out (no network?)")
                        }
                    }
                } catch (e: Exception) {
                    FileLog.e(TAG, "mDNS registration failed", e)
                }
            }

            // Listen loop starts immediately — works on 0.0.0.0 even without WiFi.
            // The car can connect once a network (hotspot/WiFi) comes up.
            while (isActive) {
                listenAndHandleOneConnection()
            }
        }
    }

    private suspend fun listenAndHandleOneConnection() {
        _serviceState.value = State.WAITING
        updateNotification(R.string.notification_waiting)
        FileLog.i(TAG, "Listening for car connection on port ${Ports.DEFAULT_PORT}...")

        // Declared outside try so the finally can scope its teardown to the
        // generation this iteration accepted (see the session-generation doc).
        var sessionId = 0
        try {
            // ─── Accept control connection (port 9637) ───
            val ctrl = Connection.accept(Ports.DEFAULT_PORT, serviceScope)
            // A brand-new session begins: mint its generation and allow the next
            // teardown to run against it. Any late teardown from a previous
            // generation is ignored by cleanupSession's id check.
            sessionId = sessionSeq.incrementAndGet()
            activeSessionId = sessionId
            handshakeReceived = false
            resetCleanupGuard(sessionId)
            controlConnection = ctrl
            activeConnection = ctrl
            _serviceState.value = State.CONNECTED
            updateNotification(R.string.notification_connected)
            FileLog.i(TAG, "Car connected (control, session #$sessionId), waiting for handshake")

            ctrl.onFrames(Channel.CONTROL) { frame -> handleControlFrame(frame) }
            ctrl.onFrames(Channel.DATA) { frame -> handleDataFrame(frame) }

            val disconnected = CompletableDeferred<Unit>()
            ctrl.onLog { msg -> FileLog.w(TAG, "ControlConn: $msg") }
            ctrl.onDisconnect {
                FileLog.i(TAG, "Car disconnected (control)")
                disconnected.complete(Unit)
            }

            ctrl.start() // heartbeat enabled (default)
            // issue #76：握手是纯被动的（handshakeJob 只在收到 HANDSHAKE_REQUEST 时才创建），
            // 对端在首帧前关闭或一直不发时，旧实现会无限期挂在 disconnected.await() 上。
            // 注意：超时只能覆盖「等 HANDSHAKE_REQUEST」这一段；收到握手后会话可能持续
            // 几十分钟，若继续套 5s 超时会把正常会话在 5s 后主动掐断（2026-11-13 S01 实测）。
            val handshakeWindowExpired = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                while (!handshakeReceived && !disconnected.isCompleted) {
                    delay(25)
                }
                handshakeReceived
            }
            if (handshakeWindowExpired == null) {
                val reason = if (handshakeReceived) {
                    "Handshake timeout: request received but not completed within ${HANDSHAKE_TIMEOUT_MS}ms"
                } else {
                    "Handshake timeout: no HANDSHAKE_REQUEST within ${HANDSHAKE_TIMEOUT_MS}ms"
                }
                FileLog.w(TAG, "$reason (session #$sessionId)")
                ctrl.disconnect()
            }
            // 未超时：正常会话在这里一直等到对端断开；握手窗口已结束，不再受 5s 限制。
            disconnected.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLog.w(TAG, "Connection error: ${e.message}")
        } finally {
            // Only tears down when this generation is still the active one —
            // a network-change restart that already accepted a new session must
            // not consume that session's cleanup guard.
            cleanupSession(sessionId)
            delay(1000)
        }
    }


    // ─── Frame Handlers ───

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        FileLog.d(TAG, "Control frame: type=0x${frame.messageType.toString(16)}")
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_REQUEST -> {
                // issue #76：先置位再解码；即使 payload 损坏，也能在超时日志里
                // 区分「对端发了但本端没处理完」与「对端一直没发」。
                handshakeReceived = true
                val req = HandshakeRequest.decode(frame.payload)
                FileLog.i(TAG, "Handshake from car: ${req.deviceName} ${req.screenWidth}x${req.screenHeight}")
                // A-02: handleHandshake does disk I/O (jar CRC), a bind (19647)
                // and package enumeration — all far too heavy for the reader
                // coroutine that dispatches this frame inline. Run it on the
                // handshake job; the previous handshake is cancelled first so a
                // rotation re-handshake cannot interleave with its predecessor.
                handshakeJob?.cancel()
                handshakeJob = serviceScope.launch(Dispatchers.IO) {
                    try {
                        handleHandshake(req)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Connection's reader also guards its listener, but this
                        // coroutine must not die silently: a failed handshake
                        // has to tear the session down, not just kill the job.
                        FileLog.e(TAG, "Handshake failed", e)
                    }
                }
            }
            // LAUNCH_APP, GO_BACK, GO_HOME, APP_UNINSTALL, APP_INFO
            // now go directly Car → VD via port 9639 Channel.CONTROL
        }
    }

    /**
     * Real physical pixel size of the default display, bypassing compatibility
     * scaling.
     *
     * **Why not `resources.displayMetrics`:** when the app is subject to
     * screen-compat mode Android scales the reported metrics — the 2026-10-09
     * test phone (real 1368x3192 @560dpi) reports a 2992px long edge there.
     * Sizing the VD from it left the IME, whose width Chinese ROMs hardcode to
     * the *true* physical width (and which ignores VD density entirely), 200px
     * (~6.7%) wider than the VD at every DPI setting — the chopped-keyboard
     * bug. `maximumWindowMetrics` (API 30+) / `getRealMetrics` (API 29) report
     * the untouched physical size.
     */
    private fun realDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val display = wm?.defaultDisplay
        if (display != null) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            return dm.widthPixels to dm.heightPixels
        }
        // No default display: fall back to compat metrics rather than crash
        // the handshake — a slightly narrow VD degrades gracefully (cropped
        // IME), an exception here kills the whole connection.
        return resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun handleHandshake(request: HandshakeRequest) {
        // P-M3: the version field was carried and never validated by any of the
        // three consumers — a peer claiming an incompatible version had its
        // trailing fields read at the wrong offsets. Reject at the boundary.
        requireSupportedProtocolVersion(request.protocolVersion)
        val conn = controlConnection ?: return
        FileLog.i(TAG, "Car display: ${request.screenWidth}x${request.screenHeight} @${request.screenDpi}dpi fps=${request.targetFps} bitrate=${request.bitrate}")
        targetFps = request.targetFps
        targetBitrate = if (request.bitrate > 0) request.bitrate else VideoConfig.DEFAULT_BITRATE

        // A handshake always leads to a fresh VD, so the teardown of whatever
        // came before must be allowed to run again (rotation re-handshake case:
        // the guard was consumed by the mid-stream VD swap below).
        resetCleanupGuard()

        cacheDefaultIme()

        // Mid-stream re-handshake: the car rotated and is reusing the control
        // connection. Tear down the old VD before creating a new one at the
        // new orientation. Old VD exits via its watchdog, restoring the panel.
        // A-03: the whole read-modify-write is under vdLock — two overlapping
        // handshakes (rotation + reconnect) used to both pass the null check
        // and both bind 19647; the loser leaked its ServerSocket.
        synchronized(vdLock) {
            vdClient?.let {
                FileLog.i(TAG, "Re-handshake: tearing down old VD (rotation)")
                it.stopVdServer()
                it.disconnect()
            }
            vdClient = null
        }

        // Create VD at car viewport size. The DPI/size computation (anti-crop
        // scale for Chinese-ROM IME hardcoding + DPI override vs. auto) lives
        // in VdDimensions (protocol-core); see it for the rationale. The phone
        // side MUST be the real physical size — see [realDisplaySize].
        val (phoneRealW, phoneRealH) = realDisplaySize()
        val (vdWidth, vdHeight, displayDpi) = VdDimensions.compute(request, phoneRealW, phoneRealH)
        FileLog.i(TAG, "VD: ${vdWidth}x${vdHeight} @${displayDpi}dpi (car ${request.screenWidth}x${request.screenHeight} @${request.screenDpi}dpi, phone real ${phoneRealW}x${phoneRealH}, override=${request.dpiOverride})")

        // Open lifecycle channel if not already open (survives re-handshakes).
        // A-03: created under vdLock so a concurrent handshake cannot double-bind.
        synchronized(vdLock) {
            vdClient ?: VirtualDisplayClient(serviceScope, this@ConnectionService).also {
                it.onStackEmpty = {
                    val c = controlConnection
                    if (c?.isConnected == true) {
                        try { c.sendControl(ControlMsg.VD_STACK_EMPTY) } catch (_: Exception) {}
                    }
                }
                it.onDisplayReady = {
                    val c = controlConnection
                    if (c?.isConnected == true) {
                        try {
                            c.sendControl(ControlMsg.VD_PORTS_BOUND)
                            FileLog.i(TAG, "Sent VD_PORTS_BOUND to car — direct video/input streaming")
                        } catch (e: Exception) {
                            FileLog.e(TAG, "Failed to send VD_PORTS_BOUND", e)
                        }
                    }
                }
                it.startListening(VirtualDisplayClient.SERVER_PORT)
                vdClient = it
                FileLog.i(TAG, "VD lifecycle channel open on 127.0.0.1:${VirtualDisplayClient.SERVER_PORT}")
            }
        }

        // 握手响应里告知车机端 jar 路径前，先确保磁盘上的 jar 就是当前 APK
        // 内嵌的那一份。放在此处可覆盖 Shizuku 与车机 ADB 两条部署路径，
        // 且早于响应发送 —— 车机端拿到路径后不会启动到旧 jar。
        val vdJarCrc = ensureVdServerJarCurrent()
        // 直接用 VdDeploy.JAR_PATH，不再与 getExternalStorageDirectory() 拼接：
        // child 的前导斜杠不重置父路径，拼接会得到 /storage/emulated/0/sdcard/DiLinkAuto
        // 这类影子目录，与实际写入位置不一致。
        val vdJarPath = VdDeploy.JAR_PATH
        // A-L18: snapshot the Shizuku decision ONCE and thread it through both
        // the response and the deploy path. Reading checkPermission() for the
        // response and isAvailable later for the deploy let a binder death
        // between the two produce "we said SHIZUKU but deployed nothing".
        val useShizuku = ShizukuManager.checkPermission()
        val connMethod = if (useShizuku) CONNECTION_METHOD_SHIZUKU else CONNECTION_METHOD_USB_ADB
        val resp = HandshakeResponse(
            accepted = true,
            deviceName = android.os.Build.MODEL,
            displayWidth = request.screenWidth,
            displayHeight = request.screenHeight,
            virtualDisplayId = -1,
            adbPort = Ports.ADB_PORT,
            vdServerJarPath = vdJarPath,
            connectionMethod = connMethod,
            vdDpi = displayDpi,
            // 让无 Shizuku 的部署端（桌面 ADB 路径）也能用放大的 VD 尺寸防 IME
            // 裁切——桌面自己的视口是编码尺寸，不是 VD 尺寸（2026-10-09 实锤：
            // 桌面按 1280x720 建 VD，IME 1368px 键盘行被切 88px）。
            vdWidth = vdWidth,
            vdHeight = vdHeight,
            // 采集端能力位（issue #72, Refs #13）。代理判据 = SDK_INT >= 30，理由与
            // 已知误报见 [phoneAudioFeatures] 的 KDoc —— 此处无法拿到真实
            // REMOTE_SUBMIX 结果（响应构造早于 vd-server 部署），不假装精确。
            supportedFeatures = phoneAudioFeatures(Build.VERSION.SDK_INT)
        )
        handshakeJob?.cancel()
        handshakeJob = serviceScope.launch(Dispatchers.IO) {
            try {
                conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, resp.encode())
                FileLog.i(TAG, "Handshake response sent")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send handshake response", e)
                return@launch
            }

            // 每次握手（首连与旋转重握手都走这里）都把手机端的诊断开关同步给车机。
            // 车机端 startConnection 会把 logWriter 重置成 BuildConfig.DEBUG，
            // 旧的实现只有用户手动切换设置时才发 LOG_TOGGLE，于是 release 下每次
            // 重连后车机日志都被静默关掉（issue #72）。放在握手响应之后，是因为
            // 车机在发 HANDSHAKE_REQUEST 之前就注册好了 Channel.DATA 处理器。
            syncLogToggle(conn)

            // If Shizuku is available, deploy VD server directly BEFORE waiting for lifecycle connection
            if (useShizuku) {
                startVdServerViaShizuku(request.screenWidth, request.screenHeight, vdWidth, vdHeight, displayDpi, vdJarCrc)
            }

            // Wait for VD to connect on the lifecycle channel already opened.
            // Runs inside handshakeJob — cancelled properly on reconnect.
            val client = vdClient ?: return@launch
            if (!client.isConnected) {
                if (client.acceptConnection(VirtualDisplayClient.SERVER_PORT)) {
                    FileLog.i(TAG, "VD server lifecycle connected (displayId=${client.displayId})")
                    withContext(Dispatchers.Main) {
                        _serviceState.value = State.STREAMING
                        updateNotification(R.string.notification_streaming)
                    }
                    appListBuilder.sendAppList(conn)
                } else {
                    FileLog.w(TAG, "VD server did not connect within timeout — tearing down")
                    withContext(Dispatchers.Main) { cleanupSession() }
                    return@launch
                }
            }
        }
    }

    /**
     * 把手机端的诊断日志开关下发给车机（issue #72）。
     *
     * 车机端 CarConnectionService 每次 `startConnection` 都会把
     * `CarLogWriter` 重置为 `BuildConfig.DEBUG`（release 下为关），只有收到
     * `DataMsg.LOG_TOGGLE` 才会重新打开。手机端只在用户手动切换设置时发过这一帧，
     * 所以「手机上开着日志 + 中途断线重连」的组合会让车机端整段会话静默无日志。
     *
     * 失败不影响会话：一帧诊断数据的丢失不能反过来把连接打掉。
     */
    private fun syncLogToggle(conn: Connection) {
        try {
            conn.sendData(DataMsg.LOG_TOGGLE, byteArrayOf(if (FileLog.enabled) 1 else 0))
            FileLog.i(TAG, "Log toggle synced to car: ${FileLog.enabled}")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to sync log toggle to car: ${e.message}")
        }
    }

    // ─── VD Server Connection ───

    /**
     * Start the VD server process directly on the phone using Shizuku.
     *
     * Shizuku provides shell-level privileges so the phone can run app_process
     * without waiting for the car's USB ADB connection. The VD server will
     * reverse-connect to localhost:19647 as usual.
     */
    private suspend fun startVdServerViaShizuku(carWidth: Int, carHeight: Int, vdWidth: Int, vdHeight: Int, dpi: Int = VideoConfig.DEFAULT_FALLBACK_DPI, jarCrc: Long = -1L) {
        try {
            val allowedPackages = appListBuilder.allowedPackages().orEmpty()
            val outcome = ShizukuVdDeployer.deploy(
                carWidth = carWidth,
                carHeight = carHeight,
                vdWidth = vdWidth,
                vdHeight = vdHeight,
                dpi = dpi,
                targetFps = targetFps,
                targetBitrate = targetBitrate,
                carHost = controlConnection?.remoteAddress ?: VdDeployArgs.CAR_HOST_ANY,
                allowedPackages = allowedPackages
            ) ?: return

            if (outcome.forcedKill) {
                FileLog.w(TAG, "Previous VD server did not exit in time — forced kill used")
            }
            if (!outcome.launched) {
                FileLog.e(TAG, "VD server launch failed (Shizuku execBackground returned false) — tearing down")
                cleanupSession()
                return
            }
            // 打出 jar CRC：测试时可直接从日志确认这次跑的是哪一版引擎，
            // 避免"装了新 APK 却跑旧代码"无从查证。
            FileLog.i(TAG, "VD server started via Shizuku: ${vdWidth}x$vdHeight @${dpi}dpi jarCrc=$jarCrc")
        } catch (e: Exception) {
            FileLog.e(TAG, "Shizuku VD server start failed", e)
        }
    }

    // ─── Data (from car) ───

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.CAR_LOG -> {
                // A-05: the DATA dispatch is asynchronous (the reader does not
                // throttle it) and MAX_PAYLOAD_SIZE used to be 128MB, so a peer
                // could push a single "log line" that allocated up to 256MB as a
                // String and entered the unbounded FileLog queue. Cap the line;
                // truncate rather than drop so the rest of the log stays usable.
                val cap = CAR_LOG_LINE_MAX_BYTES
                val bytes = frame.payload
                if (bytes.size > cap) {
                    FileLog.w(TAG, "CAR_LOG frame ${bytes.size}B exceeds $cap — truncating")
                }
                val safe = if (bytes.size > cap) bytes.copyOf(cap) else bytes
                val line = String(safe, Charsets.UTF_8)
                FileLog.i("CarLog", line)
            }
        }
    }


    // ─── Car App Install via ADB ───

    private val carAppInstallHost: CarAppInstallHost by lazy {
        CarAppInstallHost(
            context = this,
            scope = serviceScope,
            connectedCarIp = { controlConnection?.remoteAddress },
            onReinstalled = { appListBuilder.resetIconHashes() },
            ensureAssetsReady = { ensureAssetsReady() },
            onStatus = { msg -> _installStatusStatic.value = msg }
        )
    }

    fun installCarApp(explicitIp: String? = null) {
        carAppInstallHost.installCarApp(explicitIp)
    }


    // ─── Cleanup ───

    /**
     * Idempotency guard for [cleanupSession].
     *
     * `cleanupSession()` used to be callable from 7 places (two network
     * callbacks, the listen-loop `finally`, the handshake-failure path,
     * `stopEverything`, and `onDestroy`) with no guard, so a single disconnect
     * could run the whole teardown — including the vd-server kill and the
     * physical-panel restore — several times over. Observed in
     * `client-20261005-164828.log`: **1** `Car disconnected` produced **9**
     * `Force-waking physical display` lines. Each repeat raced the previous
     * one's `pkill`, which is how VDs leaked.
     *
     * Two guards now compose: the generation id (a stale teardown from an
     * already-replaced session is ignored entirely) and this AtomicBoolean (at
     * most one teardown per generation).
     */
    private val cleanupGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Allow the next [cleanupSession] to execute for [sessionId]. Called when a
     * new session is established (accept / mid-stream re-handshake) so the next
     * teardown is allowed to run again.
     */
    private fun resetCleanupGuard(sessionId: Int = activeSessionId) {
        if (sessionId == activeSessionId) cleanupGuard.set(false)
    }

    /**
     * Tear the current session down.
     *
     * @param sessionId generation this teardown belongs to; a stale value (an
     *   already-replaced session's late `finally`) is ignored so it cannot
     *   consume the active session's guard.
     */
    private fun cleanupSession(sessionId: Int = activeSessionId) {
        if (sessionId != activeSessionId) {
            FileLog.d(TAG, "cleanupSession: stale session #$sessionId (active #$activeSessionId) — skipping")
            return
        }
        if (!cleanupGuard.compareAndSet(false, true)) {
            FileLog.d(TAG, "cleanupSession: already cleaned up this session — skipping")
            return
        }
        FileLog.i(TAG, "cleanupSession: tearing down session #$sessionId")
        handshakeJob?.cancel()
        handshakeJob = null
        // Snapshot + clear shared state synchronously so a concurrent handshake
        // sees the old client gone (the RMW itself is inside vdLock).
        val client: VirtualDisplayClient?
        synchronized(vdLock) {
            client = vdClient
            vdClient = null
        }
        // issue #76：没有 VD 的空会话（对端在握手前就 EOF / 握手超时）不需要
        // power-reset、IME 恢复、KEYCODE_WAKEUP 和 MainActivity 拉起；这些动作
        // 反而会在用户没有发起投屏时点亮/打扰物理屏。只有真正创建过 VD 才恢复。
        val hadVd = client != null
        val conn = controlConnection
        controlConnection = null
        activeConnection = null
        // issue #151：会话结束清掉上报节流基准，重连后的第一帧立即上屏。
        phoneBatteryReporter?.onSessionEnded()
        appListBuilder.resetIconHashes() // Force resend icons on reconnect
        _serviceState.value = State.WAITING
        val ime = savedDefaultIme
        savedDefaultIme = null

        // Everything that can block runs on the process-lifetime teardownScope,
        // never on the caller's thread: cleanupSession is invoked from Main
        // (loop finally, stopEverything) and from the network callbacks, and
        // stopVdServer() joins a write worker for up to 1s (audit A-04).
        //
        // Order preserved: graceful CMD_STOP → channel close → panel/IME restore.
        // Runs on process-lifetime scope because onDestroy() cancels serviceScope —
        // a restore launched there would die before `cmd display power-reset`.
        teardownScope.launch {
            // Graceful stop: CMD_STOP first so the engine's readLifecycleCommands()
            // sets running=false and its finally block runs cleanup() (releases the
            // VD, restores IME + letterbox + screen settings, re-powers the panel).
            // Only if that does not take effect do we fall through to the shell kill
            // inside PhoneDisplayRestorer.
            try { client?.stopVdServer() } catch (e: Exception) { FileLog.w(TAG, "stopVdServer: ${e.message}") }
            try { client?.disconnect() } catch (e: Exception) { FileLog.w(TAG, "vdClient disconnect: ${e.message}") }
            try { conn?.disconnect() } catch (e: Exception) { FileLog.w(TAG, "control disconnect: ${e.message}") }
            // Runs on PhoneDisplayRestorer's own process-lifetime scope: the two-stage
            // stop + `cmd display power-reset` cannot be cancelled by onDestroy().
            if (hadVd) {
                displayRestorer.restore(ime)
            } else {
                FileLog.i(TAG, "cleanupSession: empty session — skip physical display restore")
            }
        }
    }

    private fun stopEverything() {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        // Explicit user stop / Service teardown must always run the teardown,
        // even if a network callback already cleaned up this session.
        resetCleanupGuard()
        cleanupSession()
        appListBuilder.resetIconHashes()
        serviceRegistration?.unregister()
        serviceRegistration = null
        _serviceState.value = State.IDLE
    }

    // ─── System ───

    private fun acquireWakeLock() = notifier.acquireWakeLock()

    private fun buildNotification(messageRes: Int): Notification =
        notifier.buildNotification(messageRes)

    private fun updateNotification(messageRes: Int) = notifier.updateNotification(messageRes)

    override fun onDestroy() {
        stopEverything()
        networkMonitor?.stop()
        networkMonitor = null
        phoneBatteryReporter?.stop()
        phoneBatteryReporter = null
        packageRemovedReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        packageRemovedReceiver = null
        notifier.releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ConnectionService"

        /** Max bytes of a single car→phone CAR_LOG line (audit A-05). */
        private const val CAR_LOG_LINE_MAX_BYTES = 8 * 1024

        /**
         * issue #76：从 accept 到首个控制帧的握手窗口。正常握手是毫秒级；
         * 5s 足够覆盖车机启动/首帧排队，又不会让空连接无限期挂着。
         */
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L

        const val ACTION_START = "com.dilinkauto.client.START"
        const val ACTION_STOP = "com.dilinkauto.client.STOP"
        const val ACTION_INSTALL_CAR = "com.dilinkauto.client.INSTALL_CAR"
        const val ACTION_ALLOWLIST_UPDATED = AllowlistRepository.ACTION_ALLOWLIST_UPDATED
        const val ALLOWLIST_PREFS = AllowlistRepository.PREFS_NAME
        const val ALLOWLIST_PACKAGES_KEY = AllowlistRepository.KEY_PACKAGES
        const val ALLOWLIST_CONFIGURED_KEY = AllowlistRepository.KEY_CONFIGURED
        const val NOTIFICATION_ID = 1001

        /** Propagate log toggle to car. Called from settings UI and onCreate. */
        fun setLogEnabled(context: android.content.Context, enabled: Boolean) {
            FileLog.enabled = enabled
            // Persist both the value and the fact that user explicitly set it
            context.getSharedPreferences(AppPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(AppPrefs.LOG_ENABLED, enabled)
                .putBoolean(AppPrefs.LOG_ENABLED_USER_SET, true).apply()
            val conn = activeConnection
            if (conn != null && conn.isConnected) {
                try { conn.sendData(DataMsg.LOG_TOGGLE, byteArrayOf(if (enabled) 1 else 0)) }
                catch (_: Exception) {}
            }
        }

        private val _serviceState = MutableStateFlow(State.IDLE)
        val serviceState: StateFlow<State> = _serviceState.asStateFlow()

        private val _installStatusStatic = MutableStateFlow("")
        val installStatusFlow: StateFlow<String> = _installStatusStatic.asStateFlow()

        var activeConnection: Connection? = null
            private set
    }
}

