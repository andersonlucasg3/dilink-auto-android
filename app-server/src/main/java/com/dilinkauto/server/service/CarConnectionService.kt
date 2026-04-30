package com.dilinkauto.server.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.adb.RemoteAdbController
import com.dilinkauto.protocol.adb.UsbAdbConnection
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Car-side service managing the connection to the phone.
 *
 * State machine with parallel prerequisites:
 *   IDLE → CONNECTING → CONNECTED → STREAMING
 *
 * CONNECTING runs two independent tracks in parallel:
 *   Track A (WiFi): discovery → TCP connect → handshake → wifiReady=true
 *   Track B (USB):  detect device → USB ADB connect → usbReady=true
 *
 * When BOTH tracks complete → CONNECTED → deploy VD server
 * When video frames arrive → STREAMING
 *
 * checkAndAdvance() is called whenever any prerequisite changes.
 */
class CarConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controlConnection: Connection? = null
    private var videoConnection: Connection? = null
    private var inputConnection: Connection? = null
    val videoDecoder = VideoDecoder()
    private var adbController: RemoteAdbController? = null
    private var phoneHost: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var consecutiveFailures = 0
    private var usbAdb: UsbAdbConnection? = null
    private var userDisconnected: Boolean
        get() = getSharedPreferences("dilinkauto", MODE_PRIVATE)
            .getBoolean("user_disconnected", false)
        set(value) = getSharedPreferences("dilinkauto", MODE_PRIVATE)
            .edit().putBoolean("user_disconnected", value).apply()

    var devMode: Boolean
        get() = getSharedPreferences("dilinkauto", MODE_PRIVATE)
                    .getBoolean("dev_mode", false)
        set(value) = getSharedPreferences("dilinkauto", MODE_PRIVATE)
            .edit().putBoolean("dev_mode", value).apply()

    // ─── Handshake ───
    private var handshakeVdDpi = VideoConfig.VIRTUAL_DISPLAY_DPI // DPI from phone (may be adjusted for DeX)

    private var vdServerJarPath = "/sdcard/DiLinkAuto/vd-server.jar"

    private val touchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "touch-sender").apply { isDaemon = true }
    }

    // ─── Parallel prerequisites ───
    @Volatile private var wifiReady = false       // WiFi TCP handshake completed
    @Volatile private var usbReady = false        // USB ADB connected to phone
    private var connectionScope: Job? = null  // Parent job for all discovery/connect coroutines
    @Volatile private var vdServerStarted = false // VD server process launched
    @Volatile private var updatingFromPhone = false // Phone is pushing an update — don't reconnect
    @Volatile private var shizukuMode = false  // Phone handles VD server via Shizuku

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val usbPermissionAction = "com.dilinkauto.server.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) onUsbDeviceAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    carLogSend("USB device detached")
                    usbAdb?.close()
                    usbAdb = null
                    usbReady = false
                }
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) {
                        carLogSend("USB permission granted")
                        connectUsbAdb(device)
                    } else {
                        carLogSend("USB permission denied")
                    }
                }
            }
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
    val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState.asStateFlow()

    private val _vdStackEmpty = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vdStackEmpty: SharedFlow<Unit> = _vdStackEmpty.asSharedFlow()

    private val _focusedApp = MutableStateFlow<String?>(null)
    val focusedApp: StateFlow<String?> = _focusedApp.asStateFlow()

    private val _videoReady = MutableStateFlow(false)
    val videoReady: StateFlow<Boolean> = _videoReady.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _shortcutsCache = MutableStateFlow<Map<String, List<AppShortcut>>>(emptyMap())
    val shortcutsCache: StateFlow<Map<String, List<AppShortcut>>> = _shortcutsCache.asStateFlow()

    enum class State { IDLE, CONNECTING, CONNECTED, STREAMING }

    inner class LocalBinder : Binder() {
        val service: CarConnectionService get() = this@CarConnectionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        videoDecoder.logSink = { msg -> carLogSend(msg) }
        acquireWakeLock()
        registerNetworkCallback()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(usbPermissionAction)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
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
                if (_state.value == State.CONNECTING && !wifiReady) {
                    startWifiTrack()
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
                startConnection()
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, Discovery.DEFAULT_PORT)
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                userDisconnected = false
                _state.value = State.CONNECTING
                connectToPhone(host, port)
            }
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun connectManual(host: String, port: Int = Discovery.DEFAULT_PORT) {
        userDisconnected = false
        _state.value = State.CONNECTING
        connectToPhone(host, port)
        startUsbTrack()  // dev mode TCP ADB or USB ADB
    }

    // ─── State Machine Core ───

    /**
     * Start both tracks in parallel. Called on ACTION_START, reconnect, etc.
     */
    private fun startConnection() {
        if (_state.value == State.STREAMING || _state.value == State.CONNECTED) return

        // Cancel ALL previous connection work before starting fresh
        connectionScope?.cancel()
        connectJob?.cancel()
        connectJob = null
        disconnectAllConnections()

        // Reset prerequisite flags (keep usbReady if USB is physically connected)
        wifiReady = false
        vdServerStarted = false
        shizukuMode = false
        _videoReady.value = false
        if (usbAdb?.isConnected != true) {
            usbReady = false
            if (usbAdb == null) usbConnecting = false // only reset if no ADB instance (auth may be pending)
        }

        userDisconnected = false
        _state.value = State.CONNECTING
        _statusMessage.value = getString(R.string.status_connecting)
        updateNotification(R.string.notification_searching)

        // Launch both tracks under a single parent job — cancelling connectionScope
        // kills all child coroutines (discovery, mDNS, USB scan)
        connectionScope = scope.launch {
            startWifiTrack()
            startUsbTrack()
        }
    }

    /**
     * Central state advancement. Called whenever any prerequisite changes.
     * Evaluates current state and advances if all conditions are met.
     */
    @Synchronized
    private fun checkAndAdvance() {
        val currentState = _state.value
        carLogSend("checkAndAdvance: state=$currentState wifi=$wifiReady usb=$usbReady vd=$vdServerStarted video=${_videoReady.value}")

        when (currentState) {
            State.IDLE -> { /* waiting for user action */ }

            State.CONNECTING -> {
                if (wifiReady && (usbReady || shizukuMode) && !vdServerStarted) {
                    carLogSend("WiFi ready${if (shizukuMode) " (Shizuku mode)" else " and USB ready"} — deploying VD server")
                    _state.value = State.CONNECTED
                    _statusMessage.value = getString(R.string.status_deploying_vd)
                    if (!shizukuMode) {
                        deployVdServer()
                    } else {
                        // Phone handles VD server via Shizuku — skip car-side deployment
                        vdServerStarted = true
                        carLogSend("Shizuku mode: phone deploys VD server, car skipping")
                    }
                } else if (wifiReady && !usbReady && !shizukuMode) {
                    _statusMessage.value = getString(R.string.status_waiting_usb)
                } else if (!wifiReady && usbReady) {
                    _statusMessage.value = getString(R.string.status_waiting_wifi)
                } else if (!wifiReady && !usbReady) {
                    _statusMessage.value = getString(R.string.status_connecting)
                }
            }

            State.CONNECTED -> {
                if (_videoReady.value) {
                    carLogSend("Video ready — streaming")
                    _state.value = State.STREAMING
                    consecutiveFailures = 0
                }
            }

            State.STREAMING -> { /* running */ }
        }
    }

    // ─── Track A: WiFi ───

    private fun startWifiTrack() {
        // Strategy 1: Gateway IP retry loop (phone is hotspot)
        // Retries every 3s until wifiReady — handles hotspot enabled after USB plug
        // and Shizuku mode where phone's service may not be listening on first attempt
        scope.launch(Dispatchers.IO) {
            delay(500)
            while (isActive && !wifiReady && _state.value == State.CONNECTING) {
                val gatewayIp = getWifiGatewayIp()
                if (gatewayIp != null) {
                    carLogSend("WiFi track: trying gateway $gatewayIp")
                    connectToPhone(gatewayIp, Discovery.DEFAULT_PORT)
                }
                delay(3000) // retry every 3 seconds
            }
        }

        // Strategy 2: mDNS (runs continuously until wifiReady)
        scope.launch {
            Discovery.discoverServices(this@CarConnectionService).collect { service ->
                if (!wifiReady && _state.value == State.CONNECTING) {
                    carLogSend("WiFi track: mDNS found ${service.host}")
                    connectToPhone(service.host, service.port)
                }
            }
        }
    }

    private var connectJob: Job? = null

    private fun connectToPhone(host: String, port: Int) {
        // Close old connections BEFORE starting new ones — prevents stale callbacks
        connectJob?.cancel()
        disconnectAllConnections()

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                // ─── Step 1: Connect control connection (port 9637) ───
                val ctrl = Connection.connect(host, port, scope)
                controlConnection = ctrl
                phoneHost = host

                ctrl.onLog { msg -> carLogSend("ControlConn: $msg") }
                ctrl.onDisconnect { scope.launch { handleDisconnect() } }
                ctrl.onFrames(Channel.CONTROL) { handleControlFrame(it) }
                ctrl.onFrames(Channel.DATA) { handleDataFrame(it) }

                ctrl.start() // heartbeat enabled (default)
                carLogSend("Control connected to $host:$port — sending handshake")

                val displayMetrics = resources.displayMetrics
                val navBarPx = navBarWidthPx(displayMetrics.density, displayMetrics.widthPixels)
                val viewportWidth = displayMetrics.widthPixels - navBarPx
                val viewportHeight = displayMetrics.heightPixels
                val handshake = HandshakeRequest(
                    deviceName = "DiLink-${android.os.Build.MODEL}",
                    screenWidth = viewportWidth,
                    screenHeight = viewportHeight,
                    screenDpi = displayMetrics.densityDpi,
                    appVersionCode = packageManager.getPackageInfo(packageName, 0).let {
                        @Suppress("DEPRECATION") it.versionCode
                    },
                    targetFps = 60,
                    appVersionName = packageManager.getPackageInfo(packageName, 0).let {
                        it.versionName ?: ""
                    }
                )
                ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, handshake.encode())

                withContext(Dispatchers.Main) { updateNotification(R.string.notification_connected) }

                // Handshake timeout
                delay(10_000)
                if (controlConnection === ctrl && !wifiReady) {
                    carLogSend("Handshake timeout — retrying")
                    ctrl.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("WiFi connect failed: ${e.message}")
                handleDisconnect()
            }
        }
    }

    /**
     * Opens video and input connections after handshake succeeds.
     * Called from handleControlFrame when HANDSHAKE_RESPONSE is received.
     */
    private fun connectVideoAndInput(host: String) {
        if (updatingFromPhone) {
            carLogSend("Skipping video/input connections — update in progress")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (updatingFromPhone) {
                    carLogSend("Skipping video/input connections — update flag set during launch")
                    return@launch
                }
                carLogSend("Connecting video (${Discovery.VIDEO_PORT}) and input (${Discovery.INPUT_PORT})...")

                val videoDef = async { Connection.connect(host, Discovery.VIDEO_PORT, scope) }
                val inputDef = async { Connection.connect(host, Discovery.INPUT_PORT, scope) }

                val video = videoDef.await()
                videoConnection = video
                video.onLog { msg -> carLogSend("VideoConn: $msg") }
                video.onDisconnect { scope.launch { handleDisconnect() } }
                video.onFrames(Channel.VIDEO) { handleVideoFrame(it) }
                video.start(enableHeartbeat = false)
                carLogSend("Video connection established")

                val input = inputDef.await()
                inputConnection = input
                input.onLog { msg -> carLogSend("InputConn: $msg") }
                input.onDisconnect { scope.launch { handleDisconnect() } }
                input.start(enableHeartbeat = false)
                carLogSend("Input connection established — all 3 connections ready")

                wifiReady = true
                checkAndAdvance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("Failed to connect video/input: ${e.message}")
                handleDisconnect()
            }
        }
    }

    private fun disconnectAllConnections() {
        videoConnection?.disconnect()
        videoConnection = null
        inputConnection?.disconnect()
        inputConnection = null
        controlConnection?.disconnect()
        controlConnection = null
    }

    // ─── Track B: USB ───

    private fun startUsbTrack() {
        if (usbReady) return // Already connected
        if (devMode) {
            carLogSend("Development mode active — using TCP ADB instead of USB")
            startTcpAdbTrack()
            return
        }
        scope.launch(Dispatchers.IO) {
            // Scan for already-connected USB device
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            for (device in usbManager.deviceList.values) {
                if (UsbAdbConnection.findAdbInterface(device) != null) {
                    carLogSend("USB track: found existing device ${device.productName}")
                    withContext(Dispatchers.Main) { onUsbDeviceAttached(device) }
                    return@launch
                }
            }
            carLogSend("USB track: no device found, waiting for attach broadcast")
        }
    }

    // ─── Dev mode: TCP ADB track (replaces USB track) ───

    private fun startTcpAdbTrack() {
        if (usbReady || adbController?.isConnected == true) return
        scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && !usbReady && _state.value == State.CONNECTING && attempts < 60) {
                // Only use phoneHost set by Track A (handshake) — never fall back
                // to gateway IP because the emulator's gateway (10.0.2.2) is the host
                // machine, not the phone.
                val host = phoneHost
                if (host != null && host != "10.0.2.2") {
                    carLogSend("Dev mode: TCP ADB connecting to $host:5555 (attempt ${attempts + 1})")
                    connectTcpAdb(host)
                    return@launch
                }
                delay(1000)
                attempts++
            }
            if (!usbReady) {
                carLogSend("Dev mode: could not determine phone IP after ${attempts}s")
            }
        }
    }

    private suspend fun connectTcpAdb(host: String) {
        if (usbReady || adbController?.isConnected == true) return

        _statusMessage.value = getString(R.string.status_connecting_tcp_adb, host)
        val keyDir = java.io.File(filesDir, "adb_keys")
        val controller = RemoteAdbController(
            phoneHost = host,
            adbPort = 5555,
            virtualDisplayId = -1,
            keyDir = keyDir
        )

        if (!controller.connect()) {
            _statusMessage.value = getString(R.string.status_tcp_adb_failed)
            carLogSend("Dev mode: TCP ADB connection failed to $host:5555")
            return
        }

        adbController = controller
        _statusMessage.value = getString(R.string.status_tcp_adb_connected)
        carLogSend("Dev mode: TCP ADB connected to $host:5555")

        controller.shell("am start -n com.dilinkauto.client/.MainActivity")
        carLogSend("Dev mode: phone app launched via TCP ADB")

        usbReady = true
        checkAndAdvance()
    }

    private fun isAdbAvailable(): Boolean =
        (adbController?.isConnected == true) || (usbAdb?.isConnected == true)

    private fun executeAdb(command: String, noWait: Boolean): Boolean {
        return when {
            adbController?.isConnected == true -> {
                if (noWait) adbController!!.shellNoWait(command)
                else adbController!!.shell(command)
            }
            usbAdb?.isConnected == true -> {
                if (noWait) usbAdb!!.shellNoWait(command) >= 0
                else { usbAdb!!.shell(command); true }
            }
            else -> false
        }
    }

    private fun onUsbDeviceAttached(device: UsbDevice) {
        if (UsbAdbConnection.findAdbInterface(device) == null) return
        userDisconnected = false

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            carLogSend("USB device with permission: ${device.productName}")
            connectUsbAdb(device)
        } else {
            carLogSend("Requesting USB permission for: ${device.productName}")
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(usbPermissionAction), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    @Volatile private var usbConnecting = false

    private fun connectUsbAdb(device: UsbDevice) {
        if (usbReady || usbConnecting) {
            carLogSend("USB ADB already ${if (usbReady) "connected" else "connecting"} — skipping")
            return
        }
        usbConnecting = true
        scope.launch(Dispatchers.IO) {
            _statusMessage.value = getString(R.string.status_connecting_usb)
            val adb = UsbAdbConnection(this@CarConnectionService)
            adb.setLogSink { msg -> carLogSend(msg) }
            if (!adb.connect(device)) {
                usbConnecting = false
                _statusMessage.value = getString(R.string.status_usb_failed)
                carLogSend("USB ADB connection failed")
                return@launch
            }
            usbAdb = adb
            _statusMessage.value = getString(R.string.status_usb_connected)
            carLogSend("USB ADB connected")

            // Write key diagnostic to phone for debugging USB auth issues
            val keyInfo = adb.keyDiagnostic()
            adb.shell("echo '$keyInfo' > /data/local/tmp/car-adb-key.log")
            carLogSend("ADB key: $keyInfo")

            // Launch phone app (don't clear task — if it's already open, just move on)
            adb.shell("am start -n com.dilinkauto.client/.MainActivity")
            carLogSend("Phone app launched via USB ADB")

            usbConnecting = false
            usbReady = true

            // If we're not in a connecting flow yet, start one
            if (_state.value == State.IDLE) {
                withContext(Dispatchers.Main) { startConnection() }
            } else {
                checkAndAdvance()
            }
        }
    }

    fun onUsbDeviceFromActivity(device: UsbDevice) {
        onUsbDeviceAttached(device)
    }

    // ─── Frame Handlers ───

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_RESPONSE -> {
                val response = HandshakeResponse.decode(frame.payload)
                carLogSend("Handshake OK: ${response.deviceName} jarPath=${response.vdServerJarPath} connMethod=${response.connectionMethod} vdDpi=${response.vdDpi}")
                _phoneName.value = response.deviceName
                vdWidth = response.displayWidth
                vdHeight = response.displayHeight
                handshakeVdDpi = response.vdDpi
                if (response.vdServerJarPath.isNotEmpty()) {
                    vdServerJarPath = response.vdServerJarPath
                }

                // Detect Shizuku mode — phone handles VD server startup
                if (response.connectionMethod == CONNECTION_METHOD_SHIZUKU) {
                    shizukuMode = true
                    carLogSend("Shizuku mode detected — phone will deploy VD server")
                }

                // Don't set wifiReady yet — first connect video + input connections.
                // wifiReady is set after all 3 connections are established.
                val host = phoneHost
                if (host != null) {
                    connectVideoAndInput(host)
                } else {
                    carLogSend("ERROR: phoneHost is null after handshake")
                }
            }
            ControlMsg.APP_STARTED -> {}
            ControlMsg.UPDATING_CAR -> {
                carLogSend("Phone is updating car app — waiting for restart")
                updatingFromPhone = true
                _statusMessage.value = getString(R.string.status_updating_car)
            }
            ControlMsg.VD_STACK_EMPTY -> {
                carLogSend("VD stack empty — switching to home")
                _focusedApp.value = null
                _vdStackEmpty.tryEmit(Unit)
            }
            ControlMsg.APP_SHORTCUTS_LIST -> {
                val msg = AppShortcutsListMessage.decode(frame.payload)
                carLogSend("Shortcuts received: ${msg.shortcuts.size} for ${msg.packageName}")
                _shortcutsCache.value = _shortcutsCache.value + (msg.packageName to msg.shortcuts)
            }
            ControlMsg.FOCUSED_APP -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                carLogSend("Focused app: $pkg")
                _focusedApp.value = pkg
            }
        }
    }

    private var videoFrameCount = 0L
    private var offscreenTexture: android.graphics.SurfaceTexture? = null
    private var offscreenSurface: android.view.Surface? = null

    private fun handleVideoFrame(frame: FrameCodec.Frame) {
        val isConfig = frame.messageType == VideoMsg.CONFIG
        videoFrameCount++
        if (isConfig || videoFrameCount % 60 == 0L) {
            carLogSend("Video ${if (isConfig) "CONFIG" else "FRAME"} size=${frame.payload.size} total=$videoFrameCount")
        }

        // Start decoder immediately on first CONFIG — don't wait for MirrorScreen's TextureView.
        // Uses an offscreen SurfaceTexture so the decoder can consume frames right away.
        // MirrorScreen will restart the decoder with the real surface when it appears.
        if (isConfig && !videoDecoder.isRunning) {
            carLogSend("Starting decoder with offscreen surface (pre-MirrorScreen)")
            val tex = android.graphics.SurfaceTexture(0)
            tex.setDefaultBufferSize(vdWidth, vdHeight)
            val surf = android.view.Surface(tex)
            offscreenTexture = tex
            offscreenSurface = surf
            videoDecoder.start(surf, vdWidth, vdHeight)
        }

        if (!_videoReady.value && !isConfig) {
            _videoReady.value = true
            carLogSend("First video frame — VD stream ready")
            checkAndAdvance()
        }
        videoDecoder.onFrameReceived(isConfig, frame.payload)
    }

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.APP_LIST -> { _appList.value = AppListMessage.decode(frame.payload).apps }
            DataMsg.NOTIFICATION_POST -> {
                val n = NotificationData.decode(frame.payload)
                // Replace existing notification with same ID (handles progress updates)
                _notifications.value = _notifications.value.filter { it.id != n.id } + n
            }
            DataMsg.NOTIFICATION_REMOVE -> {
                val n = NotificationData.decode(frame.payload)
                _notifications.value = _notifications.value.filter { it.id != n.id }
            }
            DataMsg.MEDIA_METADATA -> { _mediaMetadata.value = MediaMetadata.decode(frame.payload) }
            DataMsg.MEDIA_PLAYBACK_STATE -> { _playbackState.value = PlaybackState.decode(frame.payload) }
            DataMsg.APP_UNINSTALLED -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                _appList.value = _appList.value.filter { it.packageName != pkg }
                carLogSend("App uninstalled: $pkg — removed from grid")
            }
        }
    }

    // ─── VD Server Deploy ───

    private fun deployVdServer() {
        if (vdServerStarted) return
        if (!isAdbAvailable()) {
            carLogSend("deployVdServer: no ADB connection")
            return
        }
        scope.launch(Dispatchers.IO) {
            val displayMetrics = resources.displayMetrics
            val navBarPx = navBarWidthPx(displayMetrics.density, displayMetrics.widthPixels)
            val viewportWidth = displayMetrics.widthPixels - navBarPx
            val viewportHeight = displayMetrics.heightPixels
            val phoneDpi = handshakeVdDpi
            val dpiScale = phoneDpi.toFloat() / 160f
            val scaledH = ((VideoConfig.TARGET_SW_DP * dpiScale).toInt()) and 0x7FFFFFFE.toInt()
            val scaledW = ((scaledH * viewportWidth.toFloat() / viewportHeight).toInt()) and 0x7FFFFFFE.toInt()

            val jarPath = vdServerJarPath
            val serverPort = 19637

            val logFile = "/data/local/tmp/vd-server.log"
            val targetFps = 60  // matches handshake request
            val args = "$scaledW $scaledH $phoneDpi $serverPort $viewportWidth $viewportHeight $targetFps"

            // Kill any existing VD server
            _statusMessage.value = getString(R.string.status_preparing_vd)
            executeAdb("pkill -f VirtualDisplayServer 2>/dev/null", noWait = false)
            delay(200)

            // Launch VD server. Uses exec to replace shell with app_process — keeps ADB stream open.
            // VD server will die on disconnect; car re-deploys on reconnect.
            _statusMessage.value = getString(R.string.status_starting_vd)
            carLogSend("VD server: ${scaledW}x${scaledH}@${phoneDpi}dpi → ${viewportWidth}x${viewportHeight}")

            val cmd = "CLASSPATH=$jarPath exec app_process / " +
                    "com.dilinkauto.vdserver.VirtualDisplayServer $args" +
                    " >$logFile 2>&1"
            if (!executeAdb(cmd, noWait = true)) {
                carLogSend("VD server failed to start", "E")
                _statusMessage.value = getString(R.string.status_vd_failed)
                return@launch
            }

            vdServerStarted = true
            _statusMessage.value = getString(R.string.status_waiting_video)
            carLogSend("VD server started, waiting for video")
        }
    }

    // ─── Actions from car UI ───

    var vdWidth = 1408
        private set
    var vdHeight = 792
        private set

    /** Public log method for UI components (MirrorScreen, etc.) to route logs to phone */
    fun log(msg: String) = carLogSend(msg)

    fun releaseOffscreenSurface() {
        offscreenSurface?.release()
        offscreenSurface = null
        offscreenTexture?.release()
        offscreenTexture = null
    }

    private var touchDropCount = 0L
    private var touchSendCount = 0L

    fun sendTouchEvent(event: TouchEvent) {
        val conn = inputConnection
        if (conn == null) {
            touchDropCount++
            if (touchDropCount <= 3 || touchDropCount % 100 == 0L) {
                carLogSend("Touch DROP #$touchDropCount: inputConnection=null state=${_state.value}")
            }
            return
        }
        if (!conn.isConnected) {
            touchDropCount++
            if (touchDropCount <= 3 || touchDropCount % 100 == 0L) {
                carLogSend("Touch DROP #$touchDropCount: inputConnection not connected")
            }
            return
        }
        val payload = event.encode()
        touchExecutor.execute {
            try {
                conn.sendInput(event.action, payload)
                touchSendCount++
                if (touchSendCount <= 5 || touchSendCount % 100 == 0L) {
                    carLogSend("Touch #$touchSendCount action=${event.action} ptr=${event.pointerId} x=${"%.2f".format(event.x)} y=${"%.2f".format(event.y)}")
                }
            }
            catch (e: Exception) { carLogSend("Touch send failed: ${e.message}", "W") }
        }
    }

    fun sendTouchBatch(pointers: List<TouchEvent>) {
        val conn = inputConnection
        if (conn == null) {
            touchDropCount++
            if (touchDropCount <= 3 || touchDropCount % 100 == 0L) {
                carLogSend("Touch batch DROP #$touchDropCount: inputConnection=null state=${_state.value}")
            }
            return
        }
        if (!conn.isConnected) {
            touchDropCount++
            if (touchDropCount <= 3 || touchDropCount % 100 == 0L) {
                carLogSend("Touch batch DROP #$touchDropCount: inputConnection not connected")
            }
            return
        }
        val payload = TouchMoveBatch(pointers).encode()
        touchExecutor.execute {
            try {
                conn.sendInput(InputMsg.TOUCH_MOVE_BATCH, payload)
                touchSendCount++
                if (touchSendCount <= 5 || touchSendCount % 100 == 0L) {
                    carLogSend("Touch batch #$touchSendCount (${pointers.size} pointers)")
                }
            }
            catch (e: Exception) { carLogSend("Touch batch send failed: ${e.message}", "W") }
        }
    }

    fun launchApp(packageName: String) {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.LAUNCH_APP, LaunchAppMessage(packageName).encode()) }
            catch (e: Exception) { carLogSend("launchApp failed: ${e.message}", "E") }
        }
    }

    fun goHome() {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.GO_HOME) }
            catch (e: Exception) { carLogSend("goHome failed: ${e.message}", "E") }
        }
    }

    fun goBack() {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.GO_BACK) }
            catch (e: Exception) { carLogSend("goBack failed: ${e.message}", "E") }
        }
    }

    fun requestUninstall(packageName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                controlConnection?.sendControl(ControlMsg.APP_UNINSTALL, packageName.toByteArray(Charsets.UTF_8))
                carLogSend("Requested uninstall: $packageName")
            } catch (e: Exception) { carLogSend("requestUninstall failed: ${e.message}", "E") }
        }
    }

    fun requestAppInfo(packageName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                controlConnection?.sendControl(ControlMsg.APP_INFO, packageName.toByteArray(Charsets.UTF_8))
                carLogSend("Requested app info: $packageName")
            } catch (e: Exception) { carLogSend("requestAppInfo failed: ${e.message}", "E") }
        }
    }

    fun requestShortcuts(packageName: String) {
        // Clear stale cache entry first so the UI knows we're loading
        _shortcutsCache.value = _shortcutsCache.value - packageName
        scope.launch(Dispatchers.IO) {
            try {
                controlConnection?.sendControl(ControlMsg.APP_SHORTCUTS, packageName.toByteArray(Charsets.UTF_8))
                carLogSend("Requested shortcuts: $packageName")
            } catch (e: Exception) { carLogSend("requestShortcuts failed: ${e.message}", "E") }
        }
    }

    fun executeShortcut(packageName: String, shortcutId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val msg = AppShortcutActionMessage(packageName, shortcutId)
                controlConnection?.sendControl(ControlMsg.APP_SHORTCUT_ACTION, msg.encode())
                carLogSend("Execute shortcut: $shortcutId for $packageName")
            } catch (e: Exception) { carLogSend("executeShortcut failed: ${e.message}", "E") }
        }
    }

    fun clearNotification(id: Int, packageName: String) {
        _notifications.value = _notifications.value.filter { it.id != id || it.packageName != packageName }
        scope.launch(Dispatchers.IO) {
            try {
                val msg = ClearNotificationMessage(id, packageName)
                controlConnection?.sendData(DataMsg.NOTIFICATION_CLEAR, msg.encode())
            } catch (e: Exception) { carLogSend("clearNotification failed: ${e.message}", "E") }
        }
    }

    fun clearAllNotifications() {
        _notifications.value = emptyList()
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendData(DataMsg.NOTIFICATION_CLEAR_ALL, ByteArray(0)) }
            catch (e: Exception) { carLogSend("clearAllNotifications failed: ${e.message}", "E") }
        }
    }

    fun disconnectFromPhone() {
        carLogSend("User disconnect — will not auto-reconnect")
        userDisconnected = true
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.DISCONNECT) } catch (_: Exception) {}
            disconnectAllConnections()
        }
    }

    fun sendMediaAction(action: MediaAction) {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendData(DataMsg.MEDIA_ACTION, byteArrayOf(action.id)) }
            catch (e: Exception) { carLogSend("mediaAction failed: ${e.message}", "E") }
        }
    }

    // ─── Disconnect & Reconnect ───

    private fun handleDisconnect() {
        carLogSend("handleDisconnect — state=${_state.value} usb=${usbReady} wifi=${wifiReady}")
        // Cancel ALL ongoing connection work first
        connectionScope?.cancel()
        connectionScope = null
        connectJob?.cancel()
        connectJob = null

        // Stop decoder and close connections
        videoDecoder.stop()
        releaseOffscreenSurface()
        adbController?.disconnect()
        adbController = null
        disconnectAllConnections()

        // Reset ALL prerequisite flags
        _phoneName.value = ""
        _appList.value = emptyList()
        _videoReady.value = false
        videoFrameCount = 0
        touchSendCount = 0
        touchDropCount = 0
        wifiReady = false
        vdServerStarted = false
        // USB: keep usbReady/usbConnecting if device is physically connected.
        // Resetting usbConnecting while auth is pending causes duplicate auth dialogs.
        if (usbAdb?.isConnected != true) {
            usbReady = false
            if (usbAdb == null) usbConnecting = false // only reset if no ADB instance exists
        }

        if (updatingFromPhone) {
            _state.value = State.IDLE
            _statusMessage.value = getString(R.string.status_updating_please_wait)
            carLogSend("Disconnected during update — waiting for app restart")
            // Don't reconnect — the phone will install the new APK and restart us
            return
        }

        if (userDisconnected) {
            _state.value = State.IDLE
            // Don't clear userDisconnected — persisted until USB re-plug or manual START
            usbReady = false
            carLogSend("User disconnect — idle (persisted)")
            scope.launch { updateNotification(R.string.notification_searching) }
            return
        }

        _state.value = State.IDLE
        carLogSend("Connection lost — will reconnect")

        scope.launch {
            consecutiveFailures++
            val backoffMs = if (consecutiveFailures <= 1) 500L
                else (500L * (1L shl (consecutiveFailures - 1).coerceAtMost(4))).coerceAtMost(8000L)
            carLogSend("Reconnect backoff: ${backoffMs}ms (failures=$consecutiveFailures)")
            delay(backoffMs)
            if (_state.value == State.IDLE && !userDisconnected) {
                startConnection()
            }
        }
    }

    private fun shutdown() {
        videoDecoder.stop()
        adbController?.disconnect()
        adbController = null
        disconnectAllConnections()
        phoneHost = null
        _state.value = State.IDLE
        scope.cancel()
    }

    // ─── WiFi helpers ───

    private fun getWifiGatewayIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val gw = wm.dhcpInfo.gateway
            if (gw == 0) null
            else String.format("%d.%d.%d.%d", gw and 0xFF, (gw shr 8) and 0xFF,
                (gw shr 16) and 0xFF, (gw shr 24) and 0xFF)
        } catch (e: Exception) { null }
    }

    // ─── Car Log (sent to phone via protocol, phone writes to file) ───

    private val logBuffer = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun carLogSend(msg: String, level: String = "I") {
        when (level) {
            "D" -> Log.d(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "E" -> Log.e(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$ts][$level] $msg"
        val conn = controlConnection
        if (conn != null && conn.isConnected) {
            // Flush any buffered messages first
            while (true) {
                val buffered = logBuffer.poll() ?: break
                try { conn.sendData(DataMsg.CAR_LOG, buffered.toByteArray(Charsets.UTF_8)) }
                catch (_: Exception) { break }
            }
            try { conn.sendData(DataMsg.CAR_LOG, line.toByteArray(Charsets.UTF_8)) }
            catch (_: Exception) {}
        } else {
            // Buffer for later — cap at 10000 lines
            if (logBuffer.size < 10000) logBuffer.add(line)
        }
    }

    // ─── System ───

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiLinkAuto::CarConnectionService")
            .apply { acquire(4 * 60 * 60 * 1000L) } // 4h auto-release
    }

    private fun buildNotification(messageRes: Int): Notification {
        return NotificationCompat.Builder(this, ServerApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(messageRes))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(messageRes: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(messageRes))
    }

    override fun onDestroy() {
        shutdown()
        touchExecutor.shutdownNow()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        networkCallback?.let {
            try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
        networkCallback = null
        usbAdb?.close()
        wakeLock?.release()
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
        const val NAV_BAR_TARGET_DP = 76f

        fun navBarWidthPx(density: Float, screenWidthPx: Int): Int {
            val targetPx = (NAV_BAR_TARGET_DP * density).toInt()
            val viewport = screenWidthPx - targetPx
            return if (viewport % 2 != 0) targetPx + 1 else targetPx
        }
    }
}
