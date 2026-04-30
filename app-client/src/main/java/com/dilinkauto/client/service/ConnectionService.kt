package com.dilinkauto.client.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.display.VirtualDisplayClient
import com.dilinkauto.protocol.*
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionService : Service() {

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var controlConnection: Connection? = null
    @Volatile private var videoConnection: Connection? = null
    @Volatile private var inputConnection: Connection? = null
    @Volatile private var vdClient: VirtualDisplayClient? = null
    private var pendingAppLaunch: String? = null
    private var vdWaitJob: Job? = null
    private var vdWidth = 1304
    private var vdHeight = 792
    private var targetFps = 30
    private var serviceRegistration: Discovery.ServiceRegistration? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeDebounce: Job? = null
    private var autoUpdateAttempted = false
    private var autoUpdateFailedAt = 0L

    enum class State { IDLE, WAITING, CONNECTED, STREAMING }

    override fun onBind(intent: Intent?): IBinder? = null

    private var packageRemovedReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.rotate() // Archive previous log, start fresh
        // Clear stale static state from previous service instance
        activeConnection = null
        _serviceState.value = State.IDLE
        acquireWakeLock()
        registerNetworkCallback()
        registerPackageRemovedReceiver()
        deployAssets()
        UpdateManager.checkForUpdate(force = false)
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
                        sendAppList()
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
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
            dir.mkdirs()
            extractAsset("vd-server.jar", java.io.File(dir, "vd-server.jar"))
            extractAsset("app-server.apk", java.io.File(filesDir, "app-server.apk"))
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

    private fun extractAsset(assetName: String, target: java.io.File) {
        try {
            val assetBytes = assets.open(assetName).use { it.readBytes() }
            val assetCrc = java.util.zip.CRC32().apply { update(assetBytes) }.value

            if (target.exists()) {
                val fileCrc = java.util.zip.CRC32().apply { update(target.readBytes()) }.value
                if (fileCrc == assetCrc) {
                    FileLog.i(TAG, "$assetName up-to-date (crc=$assetCrc)")
                    return
                }
            }

            val tmp = java.io.File("${target.absolutePath}.tmp")
            tmp.writeBytes(assetBytes)
            tmp.renameTo(target)
            FileLog.i(TAG, "$assetName deployed to ${target.absolutePath} (${assetBytes.size} bytes, crc=$assetCrc)")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to extract $assetName: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.i(TAG, "Network available: $network")
                networkChangeDebounce?.cancel()
                val state = _serviceState.value
                if (state == State.WAITING) {
                    FileLog.i(TAG, "New network while WAITING — restarting listen loop")
                    cleanupSession()
                    connectionLoopJob?.cancel()
                    startConnectionLoop()
                }
            }
            override fun onLost(network: Network) {
                // Only react if the lost network is the one our connection uses.
                // Ignore unrelated network drops (mobile data, other WiFi, etc.)
                val conn = controlConnection
                if (conn != null && conn.isConnected) {
                    val cm2 = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNet = cm2.activeNetwork
                    // If another network is still active and our connection has it, ignore
                    if (activeNet != null && activeNet != network) {
                        FileLog.i(TAG, "Network lost: $network (not our active network $activeNet — ignoring)")
                        return
                    }
                }
                FileLog.i(TAG, "Network lost: $network — debouncing before reacting")
                // Debounce: delay 3s before reacting to transient network flaps.
                // 4G changes can cause brief hotspot resets that recover immediately.
                networkChangeDebounce?.cancel()
                networkChangeDebounce = serviceScope.launch {
                    delay(3000)
                    if (controlConnection?.isConnected != true) {
                        FileLog.i(TAG, "Network lost confirmed — resetting connection")
                    }
                    resetConnectionForNetworkChange()
                }
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
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
                val explicitIp = intent?.getStringExtra("car_ip")
                installCarApp(explicitIp)
            }
        }
        return START_STICKY
    }

    // ─── Connection Loop ───

    private fun startConnectionLoop() {
        // Reset auto-update state on explicit start — gives user a fresh chance
        autoUpdateAttempted = false
        autoUpdateFailedAt = 0L
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
                                port = Discovery.DEFAULT_PORT,
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
        FileLog.i(TAG, "Listening for car connection on port ${Discovery.DEFAULT_PORT}...")

        try {
            // ─── Accept control connection (port 9637) ───
            val ctrl = Connection.accept(Discovery.DEFAULT_PORT, serviceScope)
            controlConnection = ctrl
            activeConnection = ctrl
            _serviceState.value = State.CONNECTED
            updateNotification(R.string.notification_connected)
            FileLog.i(TAG, "Car connected (control), waiting for handshake")

            ctrl.onFrames(Channel.CONTROL) { frame -> handleControlFrame(frame) }
            ctrl.onFrames(Channel.DATA) { frame -> handleDataFrame(frame) }

            val disconnected = CompletableDeferred<Unit>()
            ctrl.onLog { msg -> FileLog.w(TAG, "ControlConn: $msg") }
            ctrl.onDisconnect {
                FileLog.i(TAG, "Car disconnected (control)")
                disconnected.complete(Unit)
            }

            ctrl.start() // heartbeat enabled (default)
            disconnected.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLog.w(TAG, "Connection error: ${e.message}")
        } finally {
            cleanupSession()
            delay(1000)
        }
    }

    /**
     * Accept video and input connections after handshake succeeds.
     * Called from handleHandshake() after the control connection is established.
     * Opens ServerSockets on ports 9638/9639, then accepts both in parallel.
     */
    private suspend fun acceptVideoAndInputConnections() {
        FileLog.i(TAG, "Accepting video (${Discovery.VIDEO_PORT}) and input (${Discovery.INPUT_PORT}) connections...")
        try {
            val videoDef = serviceScope.async(Dispatchers.IO) {
                Connection.accept(Discovery.VIDEO_PORT, serviceScope)
            }
            val inputDef = serviceScope.async(Dispatchers.IO) {
                Connection.accept(Discovery.INPUT_PORT, serviceScope)
            }

            val video = withTimeout(10_000) { videoDef.await() }
            videoConnection = video
            video.onLog { msg -> FileLog.w(TAG, "VideoConn: $msg") }
            video.onDisconnect {
                FileLog.i(TAG, "Car disconnected (video)")
                controlConnection?.disconnect()
            }
            video.start(enableHeartbeat = false)
            FileLog.i(TAG, "Video connection accepted")

            val input = withTimeout(10_000) { inputDef.await() }
            inputConnection = input
            input.onFrames(Channel.INPUT) { frame ->
                // Must run on IO — touch writes to localhost socket, blocked by StrictMode on Main
                serviceScope.launch(Dispatchers.IO) { handleInputFrame(frame) }
            }
            input.onLog { msg -> FileLog.w(TAG, "InputConn: $msg") }
            input.onDisconnect {
                FileLog.i(TAG, "Car disconnected (input)")
                controlConnection?.disconnect()
            }
            input.start(enableHeartbeat = false)
            FileLog.i(TAG, "Input connection accepted — all 3 connections established")
        } catch (e: Exception) {
            FileLog.e(TAG, "Failed to accept video/input connections: ${e.message}")
            controlConnection?.disconnect()
        }
    }

    // ─── Frame Handlers ───

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        FileLog.d(TAG, "Control frame: type=0x${frame.messageType.toString(16)}")
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_REQUEST -> {
                val req = HandshakeRequest.decode(frame.payload)
                FileLog.i(TAG, "Handshake from car: ${req.deviceName} ${req.screenWidth}x${req.screenHeight}")
                handleHandshake(req)
            }
            ControlMsg.LAUNCH_APP -> {
                val msg = LaunchAppMessage.decode(frame.payload)
                val client = vdClient
                if (client != null) {
                    client.launchApp(msg.packageName)
                } else {
                    pendingAppLaunch = msg.packageName
                    FileLog.i(TAG, "Queued app launch: ${msg.packageName} (VD not ready)")
                }
            }
            ControlMsg.GO_HOME -> { vdClient?.goHome() }
            ControlMsg.GO_BACK -> { vdClient?.goBack() }
            ControlMsg.VD_SERVER_READY -> {
                val port = java.nio.ByteBuffer.wrap(frame.payload).getInt()
                FileLog.i(TAG, "Car says VD server ready on port $port")
                waitForVDServer(port)
            }
            ControlMsg.APP_UNINSTALL -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                FileLog.i(TAG, "Car requested uninstall: $pkg")
                val client = vdClient
                if (client != null) {
                    client.uninstallApp(pkg)
                } else {
                    FileLog.w(TAG, "VD client not connected, cannot uninstall $pkg")
                }
            }
            ControlMsg.APP_INFO -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                FileLog.i(TAG, "Car requested app info: $pkg")
                sendAppInfoData(pkg)
            }
            ControlMsg.APP_SHORTCUTS -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                FileLog.i(TAG, "Car requested shortcuts for: $pkg")
                sendAppShortcuts(pkg)
            }
            ControlMsg.APP_SHORTCUT_ACTION -> {
                val action = AppShortcutActionMessage.decode(frame.payload)
                FileLog.i(TAG, "Car requested shortcut action: ${action.shortcutId} for ${action.packageName}")
                serviceScope.launch(Dispatchers.IO) {
                    launchShortcut(action.packageName, action.shortcutId)
                }
            }
        }
    }

    private fun handleHandshake(request: HandshakeRequest) {
        val conn = controlConnection ?: return
        FileLog.i(TAG, "Car display: ${request.screenWidth}x${request.screenHeight} @${request.screenDpi}dpi fps=${request.targetFps}")
        targetFps = request.targetFps

        val phoneDpi = VideoConfig.VIRTUAL_DISPLAY_DPI
        val dpiScale = phoneDpi.toFloat() / 160f
        val minHeightPx = (VideoConfig.TARGET_SW_DP * dpiScale).toInt()
        val carAspect = request.screenWidth.toFloat() / request.screenHeight
        val scaledH = minHeightPx and 0x7FFFFFFE.toInt()
        val scaledW = ((scaledH * carAspect).toInt()) and 0x7FFFFFFE.toInt()
        vdWidth = scaledW
        vdHeight = scaledH
        FileLog.i(TAG, "Touch mapping: ${scaledW}x${scaledH}")

        vdClient?.disconnect()
        vdClient = null

        val vdJarPath = java.io.File(
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto"),
            "vd-server.jar"
        ).absolutePath
        val connMethod = if (ShizukuManager.isAvailable) CONNECTION_METHOD_SHIZUKU else CONNECTION_METHOD_USB_ADB
        val resp = HandshakeResponse(
            accepted = true,
            deviceName = android.os.Build.MODEL,
            displayWidth = request.screenWidth,
            displayHeight = request.screenHeight,
            virtualDisplayId = -1,
            adbPort = 5555,
            vdServerJarPath = vdJarPath,
            connectionMethod = connMethod,
            vdDpi = phoneDpi
        )
        serviceScope.launch(Dispatchers.IO) {
            // Check version BEFORE sending response — determines the flow
            val carVersionName = request.appVersionName.ifEmpty { request.appVersionCode.toString() }
            val myVersionName = packageManager.getPackageInfo(packageName, 0).let {
                it.versionName ?: @Suppress("DEPRECATION") it.versionCode.toString()
            }
            // Skip auto-update if a previous attempt failed recently (5min cooldown)
            val updateCooldown = autoUpdateFailedAt > 0L &&
                System.currentTimeMillis() - autoUpdateFailedAt < 5 * 60 * 1000L
            val needsUpdate = UpdateManager.compareVersions(myVersionName, carVersionName) > 0
                && !autoUpdateAttempted && !updateCooldown

            if (needsUpdate) {
                // ─── UPDATE FLOW ───
                // Send handshake response first so the car knows we accepted
                try {
                    conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, resp.encode())
                    FileLog.i(TAG, "Handshake response sent (update needed)")
                } catch (e: Exception) {
                    FileLog.e(TAG, "Failed to send handshake response", e)
                    return@launch
                }

                // Tell the car we're about to update it — don't reconnect, just wait.
                try {
                    conn.sendControl(ControlMsg.UPDATING_CAR)
                    FileLog.i(TAG, "Sent UPDATING_CAR to car")
                } catch (_: Exception) {}

                // Do NOT start VD wait or send app list — the car will restart after update.
                autoUpdateAttempted = true
                FileLog.i(TAG, "Car app outdated ($carVersionName < $myVersionName) — updating, then waiting for reconnect")
                _installStatusStatic.value = getString(R.string.status_auto_update, carVersionName, myVersionName)
                autoUpdateCarApp(conn)
                // autoUpdateCarApp restarts the car app — it will reconnect with the new version.
                // We disconnect and go back to WAITING.
                delay(2000) // give the update a moment to start
                FileLog.i(TAG, "Update initiated — disconnecting to wait for car reconnect")
                withContext(Dispatchers.Main) { cleanupSession() }
            } else {
                // ─── NORMAL FLOW ───
                // Send handshake response first — the car will then connect
                // on video (9638) and input (9639) ports.
                try {
                    conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, resp.encode())
                    FileLog.i(TAG, "Handshake response sent")
                } catch (e: Exception) {
                    FileLog.e(TAG, "Failed to send handshake response", e)
                    return@launch
                }

                if (UpdateManager.compareVersions(myVersionName, carVersionName) > 0) {
                    FileLog.i(TAG, "Car app outdated ($carVersionName) — update already attempted this session, proceeding")
                } else {
                    FileLog.i(TAG, "Car app up-to-date ($carVersionName)")
                }

                // Accept video and input connections from car
                acceptVideoAndInputConnections()

                // Now that video connection is established, open VD ServerSocket
                // and wait for VD server to connect
                val vidConn = videoConnection
                if (vidConn == null) {
                    FileLog.e(TAG, "Video connection not established — cannot start VD")
                    return@launch
                }
                waitForVDServer(VD_SERVER_PORT)

                // If Shizuku is available, start the VD server directly
                // (no need for the car's USB ADB to deploy it)
                if (ShizukuManager.isAvailable) {
                    startVdServerViaShizuku(request.screenWidth, request.screenHeight)
                }

                sendAppList()
            }
        }
    }

    // ─── VD Server Connection ───

    private fun waitForVDServer(port: Int) {
        // If already connected, don't restart
        if (vdClient?.isConnected == true) {
            FileLog.d(TAG, "VD server already connected — skipping")
            return
        }
        // Cancel any previous wait and clean up
        vdWaitJob?.cancel()
        vdClient?.disconnect()
        vdClient = null

        // Open ServerSocket SYNCHRONOUSLY so it's ready before the car deploys the VD server.
        val vidConn = videoConnection ?: return
        val ctrlConn = controlConnection ?: return
        val client = VirtualDisplayClient(vidConn, ctrlConn, serviceScope, this)
        client.startListening(port)

        vdWaitJob = serviceScope.launch(Dispatchers.IO) {
            if (client.acceptConnection(port)) {
                vdClient = client
                FileLog.i(TAG, "VD server connected (displayId=${client.displayId})")
                // Configure accessibility service for direct touch injection on the VD.
                // The VD server's IInputManager.injectInputEvent requires INJECT_EVENTS
                // permission, which shell UID (2000) lacks on many production devices.
                InputInjectionService.instance?.setVirtualDisplay(client.displayId, vdWidth, vdHeight)
                withContext(Dispatchers.Main) {
                    _serviceState.value = State.STREAMING
                    updateNotification(R.string.notification_streaming)
                }
                pendingAppLaunch?.let { pkg ->
                    FileLog.i(TAG, "Launching queued app: $pkg")
                    client.launchApp(pkg)
                    pendingAppLaunch = null
                }
            } else {
                FileLog.w(TAG, "VD server did not connect within timeout")
            }
        }
    }

    /**
     * Start the VD server process directly on the phone using Shizuku.
     *
     * Shizuku provides shell-level privileges so the phone can run app_process
     * without waiting for the car's USB ADB connection. The VD server will
     * reverse-connect to localhost:19637 as usual.
     */
    private fun startVdServerViaShizuku(carWidth: Int, carHeight: Int) {
        if (!ShizukuManager.isAvailable) {
            FileLog.w(TAG, "Shizuku not available — cannot start VD server")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val phoneDpi = VideoConfig.VIRTUAL_DISPLAY_DPI
                val dpiScale = phoneDpi.toFloat() / 160f
                val scaledH = ((VideoConfig.TARGET_SW_DP * dpiScale).toInt()) and 0x7FFFFFFE.toInt()
                val scaledW = ((scaledH * carWidth.toFloat() / carHeight).toInt()) and 0x7FFFFFFE.toInt()
                val jarPath = java.io.File(
                    java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto"),
                    "vd-server.jar"
                ).absolutePath
                val logFile = "/data/local/tmp/vd-server.log"
                val args = "$scaledW $scaledH $phoneDpi $VD_SERVER_PORT $carWidth $carHeight $targetFps"

                // Kill any existing VD server process
                ShizukuManager.execAndWait("pkill -f VirtualDisplayServer 2>/dev/null")
                delay(200)

                // Launch VD server via app_process (shell UID 2000) using Shizuku.
                // The command runs detached (&) so it doesn't block the Shizuku service call.
                val cmd = "CLASSPATH=$jarPath exec app_process / " +
                        "com.dilinkauto.vdserver.VirtualDisplayServer $args" +
                        " >$logFile 2>&1"
                ShizukuManager.execBackground(cmd)
                FileLog.i(TAG, "VD server started via Shizuku: ${scaledW}x${scaledH}")
            } catch (e: Exception) {
                FileLog.e(TAG, "Shizuku VD server start failed", e)
            }
        }
    }

    /**
     * Auto-update car app via dadb when handshake reveals outdated version.
     * Gets the car's IP from the WiFi gateway (car connects to phone's hotspot).
     */
    private fun autoUpdateCarApp(@Suppress("UNUSED_PARAMETER") conn: Connection) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ensureAssetsReady()
                val apkFile = java.io.File(filesDir, "app-server.apk")
                if (!apkFile.exists()) {
                    FileLog.w(TAG, "Auto-update: car APK not found")
                    return@launch
                }

                // Get car IP from the active TCP connection (most reliable)
                val carIp = controlConnection?.remoteAddress
                    ?: findCarAdb()
                if (carIp == null) {
                    FileLog.w(TAG, "Auto-update: can't determine car IP")
                    return@launch
                }

                FileLog.i(TAG, "Auto-updating car app at $carIp:5555...")
                _installStatusStatic.value = getString(R.string.car_install_status_connecting_to, carIp)
                val privKey = java.io.File(filesDir, "adbkey")
                val pubKey = java.io.File(filesDir, "adbkey.pub")
                if (!privKey.exists()) {
                    filesDir.mkdirs()
                    AdbKeyPair.generate(privKey, pubKey)
                }
                val keyPair = AdbKeyPair.read(privKey, pubKey)

                val dadbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val dadb: Dadb? = try {
                    val future = dadbExecutor.submit<Dadb> {
                        Dadb.create(carIp, 5555, keyPair)
                    }
                    try {
                        future.get(15, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        FileLog.w(TAG, "Auto-update Dadb.create() timed out — will retry in 5min")
                        null
                    }
                } finally {
                    dadbExecutor.shutdownNow()
                }

                if (dadb == null) {
                    _installStatusStatic.value = getString(R.string.car_install_status_auth_needed)
                    autoUpdateFailedAt = System.currentTimeMillis()
                    return@launch
                }

                try {
                    _installStatusStatic.value = "Pushing car APK..."
                    val remotePath = "/data/local/tmp/app-server.apk"
                    dadb.push(apkFile, remotePath)
                    _installStatusStatic.value = "Installing car app..."
                    val result = dadb.shell("pm install -r $remotePath").allOutput
                    FileLog.i(TAG, "Auto-update result: ${result.trim()}")
                    if (result.contains("Success")) {
                        _installStatusStatic.value = getString(R.string.status_auto_update_complete)
                        FileLog.i(TAG, "Car app auto-updated — restarting")
                        dadb.shell("am start --activity-clear-task -n com.dilinkauto.server/.MainActivity")
                    } else {
                        _installStatusStatic.value = getString(R.string.status_update_failed, result.trim())
                        autoUpdateFailedAt = System.currentTimeMillis()
                        FileLog.w(TAG, "Auto-update failed: ${result.trim()} — will retry in 5min")
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatusStatic.value = getString(R.string.status_auto_update_failed, e.message ?: "unknown")
                autoUpdateFailedAt = System.currentTimeMillis()
                FileLog.e(TAG, "Auto-update failed: ${e.message} — will retry in 5min")
            }
        }
    }

    // ─── Data (from car) ───

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.CAR_LOG -> {
                val line = String(frame.payload, Charsets.UTF_8)
                FileLog.i("CarLog", line)
            }
            DataMsg.NOTIFICATION_CLEAR -> {
                val msg = ClearNotificationMessage.decode(frame.payload)
                NotificationService.instance?.cancelNotification(msg.packageName, msg.id)
            }
            DataMsg.NOTIFICATION_CLEAR_ALL -> {
                NotificationService.instance?.cancelAll()
            }
        }
    }

    // ─── Input ───

    private var inputFrameCount = 0L

    private fun handleInputFrame(frame: FrameCodec.Frame) {
        val client = vdClient
        inputFrameCount++
        if (inputFrameCount <= 3 || inputFrameCount % 100 == 0L) {
            FileLog.i(TAG, "handleInputFrame #$inputFrameCount type=0x${frame.messageType.toString(16)} vdClient=${client != null} connected=${client?.isConnected}")
        }

        // Use VD server direct injection if IInputManager is available (low latency),
        // otherwise fall back to accessibility gestures (guaranteed to work).
        val useDirect = client != null && client.isConnected && client.hasDirectInjection
        val cl = if (useDirect) client!! else null

        try {
            if (frame.messageType == InputMsg.TOUCH_MOVE_BATCH) {
                val batch = TouchMoveBatch.decode(frame.payload)
                if (cl != null) {
                    val w = vdWidth
                    val h = vdHeight
                    for (p in batch.pointers) {
                        cl.touch(1, p.pointerId, (p.x * w).toInt(), (p.y * h).toInt(), p.pressure)
                    }
                } else {
                    for (p in batch.pointers) {
                        InputInjectionService.instance?.injectTouch(TouchEvent(
                            action = InputMsg.TOUCH_MOVE,
                            pointerId = p.pointerId,
                            x = p.x, y = p.y,
                            pressure = p.pressure,
                            timestamp = p.timestamp
                        ))
                    }
                }
                return
            }

            val event = TouchEvent.decode(frame.payload)
            if (cl != null) {
                val w = vdWidth
                val h = vdHeight
                val pixelX = (event.x * w).toInt()
                val pixelY = (event.y * h).toInt()
                val action = when (event.action) {
                    InputMsg.TOUCH_DOWN -> 0
                    InputMsg.TOUCH_MOVE -> 1
                    InputMsg.TOUCH_UP -> 2
                    else -> return
                }
                cl.touch(action, event.pointerId, pixelX, pixelY, event.pressure)
            } else {
                InputInjectionService.instance?.injectTouch(event)
            }
        } catch (e: Exception) {
            FileLog.e(TAG, "handleInputFrame error type=0x${frame.messageType.toString(16)}: ${e.message}", e)
        }
    }

    // ─── Car App Install via ADB ───

    private val _installStatus get() = _installStatusStatic

    fun installCarApp(explicitIp: String? = null) {
        serviceScope.launch(Dispatchers.IO) {
            var keepStatus = false
            try {
                ensureAssetsReady()
                val apkFile = java.io.File(filesDir, "app-server.apk")
                if (!apkFile.exists()) {
                    _installStatus.value = getString(R.string.car_install_status_car_apk_not_found)
                    FileLog.w(TAG, "No embedded car APK")
                    return@launch
                }

                _installStatus.value = if (explicitIp != null) getString(R.string.car_install_status_connecting_to, explicitIp) else getString(R.string.car_install_status_searching)
                val carIp = if (!explicitIp.isNullOrBlank()) {
                    if (probePort(explicitIp, 5555)) explicitIp else {
                        _installStatus.value = getString(R.string.car_install_status_not_reachable, explicitIp)
                        null
                    }
                } else findCarAdb()
                if (carIp == null) {
                    _installStatus.value = getString(R.string.car_install_status_car_not_found)
                    FileLog.w(TAG, "Could not find car ADB on USB or network")
                    return@launch
                }

                _installStatus.value = getString(R.string.car_install_status_connecting_to, carIp)
                FileLog.i(TAG, "Connecting to car ADB at $carIp:5555")
                val privKey = java.io.File(filesDir, "adbkey")
                val pubKey = java.io.File(filesDir, "adbkey.pub")
                if (!privKey.exists()) {
                    filesDir.mkdirs()
                    AdbKeyPair.generate(privKey, pubKey)
                }
                val keyPair = AdbKeyPair.read(privKey, pubKey)

                // Dadb.create() does blocking socket I/O that coroutine cancellation
                // cannot interrupt. Use Future.get(timeout) for reliable timeout.
                FileLog.d(TAG, "Attempting Dadb.create() (15s timeout)...")
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val dadb: Dadb? = try {
                    val future = executor.submit<Dadb> {
                        Dadb.create(carIp, 5555, keyPair)
                    }
                    try {
                        future.get(15, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        FileLog.w(TAG, "Dadb.create() timed out after 15s — likely waiting for car auth dialog")
                        null
                    }
                } finally {
                    executor.shutdownNow()
                }

                if (dadb == null) {
                    _installStatus.value = getString(R.string.car_install_status_auth_needed)
                    keepStatus = true
                    return@launch
                }
                FileLog.d(TAG, "Dadb.create() succeeded")

                try {
                    _installStatus.value = getString(R.string.car_install_status_checking_version)
                    val versionOutput = dadb.shell(
                        "dumpsys package com.dilinkauto.server 2>/dev/null | grep versionName"
                    ).allOutput
                    val installedVersionName = Regex("""versionName=(\S+)""")
                        .find(versionOutput)?.groupValues?.get(1) ?: "0"
                    val myVersionName = packageManager.getPackageInfo(packageName, 0).let {
                        it.versionName ?: @Suppress("DEPRECATION") it.versionCode.toString()
                    }
                    FileLog.i(TAG, "Car app: installed=$installedVersionName, embedded=$myVersionName")

                    if (UpdateManager.compareVersions(myVersionName, installedVersionName) <= 0) {
                        _installStatus.value = getString(R.string.car_install_status_already_up_to_date, installedVersionName)
                        return@launch
                    }

                    _installStatus.value = getString(R.string.car_install_status_pushing_apk, apkFile.length() / 1024 / 1024)
                    val remotePath = "/data/local/tmp/app-server.apk"
                    dadb.push(apkFile, remotePath)
                    FileLog.i(TAG, "Car APK pushed (${apkFile.length()} bytes)")

                    _installStatus.value = getString(R.string.car_install_status_installing_version, myVersionName)
                    val result = dadb.shell("pm install -r $remotePath").allOutput
                    FileLog.i(TAG, "Install result: ${result.trim()}")

                    if (result.contains("Success")) {
                        _installStatus.value = getString(R.string.car_install_status_launching_car_app)
                        dadb.shell("am start --activity-clear-task -n com.dilinkauto.server/.MainActivity")
                        _installStatus.value = getString(R.string.car_install_status_car_installed, myVersionName)
                    } else {
                        _installStatus.value = getString(R.string.car_install_status_failed, result.trim())
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatus.value = getString(R.string.car_install_status_error, e.message ?: "unknown")
                FileLog.e(TAG, "Car app install failed", e)
            } finally {
                if (!keepStatus) {
                    delay(5000)
                    _installStatus.value = ""
                }
            }
        }
    }

    private suspend fun findCarAdb(): String? {
        // 1. Check the control connection's remote address (car is already connected)
        controlConnection?.remoteAddress?.let { ip ->
            if (probePort(ip, 5555)) {
                FileLog.i(TAG, "Found car ADB at $ip (control connection)")
                return ip
            }
        }

        // 2. Scan ALL local subnets (phone may be on both home WiFi + hotspot)
        val subnetIps = getLocalSubnetIps()
        val prefixes = subnetIps.map { it.substringBeforeLast(".") }.distinct()
        FileLog.d(TAG, "Local subnets: $subnetIps (prefixes: $prefixes)")

        // 3. ARP table (may be blocked on Android 14+)
        try {
            for (line in java.io.File("/proc/net/arp").readLines().drop(1)) {
                val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                if (ip == "0.0.0.0") continue
                if (subnetIps.contains(ip)) continue
                if (probePort(ip, 5555)) {
                    FileLog.i(TAG, "Found car ADB at $ip (ARP)")
                    return ip
                }
            }
        } catch (e: Exception) {
            FileLog.d(TAG, "ARP not available: ${e.message}")
        }

        // 4. Neighbor cache
        try {
            for (line in Runtime.getRuntime().exec(arrayOf("ip", "neigh")).inputStream.bufferedReader().readText().lines()) {
                val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                if (!ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) continue
                if (subnetIps.contains(ip)) continue
                if (probePort(ip, 5555)) {
                    FileLog.i(TAG, "Found car ADB at $ip (neighbor)")
                    return ip
                }
            }
        } catch (_: Exception) {}

        // 5. Parallel scan on ALL subnets
        for (prefix in prefixes) {
            FileLog.i(TAG, "Scanning $prefix.0/24 for ADB...")
            val startMs = System.currentTimeMillis()
            val result = probeSubnetConcurrent(prefix, ownIps = subnetIps, maxConcurrent = 32)
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                FileLog.i(TAG, "Found car ADB at $result ($prefix.0/24, ${elapsed}ms)")
                return result
            }
            FileLog.d(TAG, "$prefix.0/24: no ADB found (${elapsed}ms)")
        }

        // 6. Gateway
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val gw = wm.dhcpInfo.gateway
            if (gw != 0) {
                val ip = String.format("%d.%d.%d.%d",
                    gw and 0xFF, (gw shr 8) and 0xFF,
                    (gw shr 16) and 0xFF, (gw shr 24) and 0xFF)
                if (!subnetIps.contains(ip) && probePort(ip, 5555)) {
                    FileLog.i(TAG, "Found car ADB at $ip (gateway)")
                    return ip
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun getLocalSubnetIps(): List<String> {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                        .map { it.hostAddress!! }
                }
                .filter { !it.startsWith("127.") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scans a /24 subnet for port 5555 using parallel concurrent probes.
     * Probes the full 1-254 range in batches of [maxConcurrent], 150ms timeout each.
     * This finds the car reliably regardless of its DHCP-assigned IP.
     */
    private suspend fun probeSubnetConcurrent(
        prefix: String, ownIps: List<String>, maxConcurrent: Int = 32
    ): String? = coroutineScope {
        // Skip .0 (network) and .255 (broadcast), and own IPs
        val ownIpSet = ownIps.toSet()
        val ips = (1..254).map { "$prefix.$it" }.filter { it !in ownIpSet }
        ips.chunked(maxConcurrent).forEach { batch ->
            val results = batch.map { ip ->
                async(Dispatchers.IO) { if (probePortRaw(ip, 5555)) ip else null }
            }
            results.forEach { deferred ->
                val found = deferred.await()
                if (found != null) {
                    coroutineContext.cancelChildren() // cancel remaining probes
                    return@coroutineScope found
                }
            }
        }
        null
    }

    /** Non-suspend port probe (150ms timeout) for use in parallel scans */
    private fun probePortRaw(ip: String, port: Int): Boolean {
        return try {
            val ch = java.nio.channels.SocketChannel.open()
            ch.configureBlocking(false)
            ch.connect(java.net.InetSocketAddress(ip, port))
            val deadline = System.currentTimeMillis() + 150
            try {
                while (!ch.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) return false
                    Thread.sleep(5)
                }
                true
            } finally {
                ch.close()
            }
        } catch (_: Exception) { false }
    }

    private suspend fun probePort(ip: String, port: Int): Boolean {
        return try {
            val ch = java.nio.channels.SocketChannel.open()
            ch.configureBlocking(false)
            ch.connect(java.net.InetSocketAddress(ip, port))
            val deadline = System.currentTimeMillis() + 500
            try {
                while (!ch.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) return false
                    kotlinx.coroutines.delay(50)
                }
                true
            } finally {
                ch.close()
            }
        } catch (_: Exception) { false }
    }

    // ─── App List ───

    // Tracks the last icon hash sent per package — survives across reconnections
    // within the same service lifetime to avoid re-sending unchanged icons.
    private val lastSentIconHash = mutableMapOf<String, String>()

    private fun sendAppList() {
        val conn = controlConnection ?: return
        val pm = packageManager

        serviceScope.launch(Dispatchers.IO) {
            try {
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { info ->
                    val pkg = info.activityInfo.packageName
                    val hash = ClientApp.iconCache.getIconHash(pkg, 96)
                    // Only include icon data if the hash differs from last sent
                    val prevHash = lastSentIconHash[pkg]
                    val iconPng = if (hash.isNotEmpty() && hash == prevHash) {
                        ByteArray(0) // car can use its cached icon
                    } else {
                        lastSentIconHash[pkg] = hash
                        ClientApp.iconCache.getOrLoad(pkg, 96)
                    }
                    AppInfo(
                        pkg,
                        info.loadLabel(pm).toString(),
                        categorizeApp(pkg),
                        iconPng,
                        hash
                    )
                }.sortedBy { it.category.id }

                conn.sendData(DataMsg.APP_LIST, AppListMessage(apps).encode())
                val skipped = apps.count { it.iconPng.isEmpty() }
                FileLog.i(TAG, "App list sent: ${apps.size} apps (${skipped} icons skipped/unchanged)")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send app list", e)
            }
        }
    }

    // ─── App Shortcuts ───

    private fun sendAppInfoData(packageName: String) {
        val conn = controlConnection ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val pi = pm.getPackageInfo(packageName, 0)
                val ai = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(ai).toString()
                val msg = AppInfoDataMessage(
                    packageName = packageName,
                    appName = appName,
                    versionName = pi.versionName ?: "",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                        pi.longVersionCode else pi.versionCode.toLong(),
                    installTime = pi.firstInstallTime,
                    targetSdk = pi.applicationInfo.targetSdkVersion
                )
                conn.sendData(DataMsg.APP_INFO_DATA, msg.encode())
                FileLog.i(TAG, "Sent app info for $packageName v${pi.versionName}")
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to query/send app info for $packageName: ${e.message}")
            }
        }
    }

    private fun sendAppShortcuts(packageName: String) {
        val conn = controlConnection ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val shortcuts = queryShortcuts(packageName)
                val msg = AppShortcutsListMessage(packageName, shortcuts)
                conn.sendControl(ControlMsg.APP_SHORTCUTS_LIST, msg.encode())
                FileLog.i(TAG, "Sent ${shortcuts.size} shortcuts for $packageName")
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to query/send shortcuts for $packageName: ${e.message}")
                // Send empty list so car doesn't hang waiting
                try {
                    val msg = AppShortcutsListMessage(packageName, emptyList())
                    conn.sendControl(ControlMsg.APP_SHORTCUTS_LIST, msg.encode())
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun queryShortcuts(packageName: String): List<AppShortcut> {
        FileLog.i(TAG, "Querying shortcuts for $packageName: shizuku=${ShizukuManager.isAvailable} vdClient=${vdClient != null} vdConnected=${vdClient?.isConnected}")
        // True when Shizuku already proved cmd shortcut is unavailable on this device,
        // so we can skip the redundant VD server attempt (both run the same command).
        var cmdShortcutUnavailable = false
        // Try Shizuku shell first — has full access to shortcut data
        if (ShizukuManager.isAvailable) {
            try {
                val output = ShizukuManager.execAndWait("cmd shortcut get-shortcuts --package $packageName")
                if (!output.isNullOrEmpty()) {
                    val parsed = parseCmdShortcutOutput(output, packageName)
                    if (parsed.isNotEmpty()) {
                        FileLog.i(TAG, "Shizuku: ${parsed.size} shortcuts for $packageName")
                        return parsed
                    }
                    // cmd shortcut unavailable on this device — try dumpsys via Shizuku
                    cmdShortcutUnavailable = true
                    FileLog.d(TAG, "Shizuku: cmd shortcut returned ${output.length} chars but parsed empty, trying dumpsys")
                    val dumpOutput = ShizukuManager.execAndWait("dumpsys shortcut $packageName 2>&1")
                    if (!dumpOutput.isNullOrBlank()) {
                        val dumpParsed = parseCmdShortcutOutput(dumpOutput, packageName)
                        if (dumpParsed.isNotEmpty()) {
                            FileLog.i(TAG, "Shizuku dumpsys: ${dumpParsed.size} shortcuts for $packageName")
                            return dumpParsed
                        }
                    }
                    FileLog.i(TAG, "Shizuku: cmd shortcut unavailable, skipping VD server")
                }
            } catch (e: Exception) {
                FileLog.w(TAG, "Shizuku shortcut query failed for $packageName: ${e.message}")
            }
        }
        // Try VD server — skip if Shizuku already proved cmd shortcut is unavailable
        if (!cmdShortcutUnavailable) {
            val vd = vdClient
            if (vd != null && vd.isConnected) {
                FileLog.i(TAG, "VD server path: querying shortcuts for $packageName")
                try {
                    val output = vd.queryShortcuts(packageName)
                    if (!output.isNullOrBlank()) {
                        val parsed = parseCmdShortcutOutput(output, packageName)
                        if (parsed.isNotEmpty()) {
                            FileLog.i(TAG, "VD server returned ${parsed.size} shortcuts for $packageName")
                            return parsed
                        } else {
                            FileLog.w(TAG, "VD server returned output but parsed empty for $packageName")
                        }
                    } else {
                        FileLog.w(TAG, "VD server returned empty/null output for $packageName")
                    }
                } catch (e: Exception) {
                    FileLog.w(TAG, "VD shortcut query failed for $packageName: ${e.message}")
                }
            }
        }
        // Fallback: read shortcuts directly from the APK's XML resource.
        // Necessary when "cmd shortcut" service is unavailable (Samsung, Xiaomi, etc.)
        val apkShortcuts = queryShortcutsFromApkXml(packageName)
        if (apkShortcuts.isNotEmpty()) {
            FileLog.i(TAG, "APK XML: ${apkShortcuts.size} shortcuts for $packageName")
            return apkShortcuts
        }
        // Last resort: LauncherApps API (may fail with "Caller can't access shortcut information")
        return try {
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            val user = android.os.Process.myUserHandle()
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            }
            (launcherApps.getShortcuts(query, user) ?: emptyList())
                .map { AppShortcut(it.id, it.shortLabel.toString(), it.longLabel.toString()) }
        } catch (e: Exception) {
            FileLog.w(TAG, "Shortcut query failed for $packageName: ${e.message}")
            emptyList()
        }
    }

    /** Parse output from 'cmd shortcut get-shortcuts' shell command. */
    private fun parseCmdShortcutOutput(output: String, expectedPackage: String): List<AppShortcut> {
        val shortcuts = mutableListOf<AppShortcut>()
        var currentId: String? = null
        var shortLabel = ""
        var longLabel = ""
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val isIndented = line.startsWith(" ") || line.startsWith("\t")

            // Package header line: "com.example.app:" (not indented)
            if (!isIndented && trimmed.endsWith(":")) {
                if (currentId != null) {
                    shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
                }
                currentId = null; shortLabel = ""; longLabel = ""
                continue
            }
            // Shortcut id line (indented, ends with ":")
            if (isIndented && trimmed.endsWith(":") && !trimmed.contains(" ")) {
                if (currentId != null) {
                    shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
                }
                currentId = trimmed.removeSuffix(":")
                shortLabel = ""; longLabel = ""
                continue
            }
            // Label lines
            if (currentId != null) {
                if (trimmed.startsWith("ShortLabel:")) {
                    shortLabel = trimmed.removePrefix("ShortLabel:").trim()
                } else if (trimmed.startsWith("LongLabel:")) {
                    longLabel = trimmed.removePrefix("LongLabel:").trim()
                }
            }
        }
        if (currentId != null) {
            shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
        }
        return shortcuts
    }

    /**
     * Reads an app's shortcuts.xml resource directly from its APK using AssetManager.
     * This bypasses the ShortcutService entirely, working on devices where
     * "cmd shortcut" is unavailable (e.g. Samsung One UI).
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun queryShortcutsFromApkXml(packageName: String): List<AppShortcut> {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            val shortcuts = mutableListOf<AppShortcut>()

            // Build an AssetManager pointing at the target APK
            val am = android.content.res.AssetManager::class.java.newInstance()
            val addPath = android.content.res.AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java).apply { isAccessible = true }
            val cookie = addPath.invoke(am, ai.publicSourceDir) as Int
            if (cookie == 0) return emptyList()

            val getResId = android.content.res.AssetManager::class.java
                .getDeclaredMethod("getResourceIdentifier", String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            val resId = getResId.invoke(am, "shortcuts", "xml", ai.packageName) as Int
            if (resId == 0) return emptyList()

            val res = android.content.res.Resources(am, resources.displayMetrics, resources.configuration)
            val parser = res.getXml(resId)

            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "shortcut") {
                    val id = parser.getAttributeValue(null, "shortcutId")
                        ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutId")
                    val label = parser.getAttributeValue(null, "shortcutShortLabel")
                        ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutShortLabel")
                    if (id != null) {
                        val displayLabel = label ?: id
                        val longLabel = parser.getAttributeValue(null, "shortcutLongLabel")
                            ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutLongLabel")
                            ?: displayLabel
                        shortcuts.add(AppShortcut(id, displayLabel, longLabel))
                    }
                }
                eventType = parser.nextToken()
            }
            parser.close()
            FileLog.i(TAG, "APK XML: ${shortcuts.size} shortcuts for $packageName")
            shortcuts
        } catch (e: Exception) {
            FileLog.w(TAG, "APK XML shortcut parse failed for $packageName: ${e.message}")
            emptyList()
        }
    }

    private fun launchShortcut(packageName: String, shortcutId: String) {
        try {
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return
            val user = android.os.Process.myUserHandle()
            val displayId = vdClient?.displayId
            val options = if (displayId != null && displayId >= 0) {
                android.app.ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                }.toBundle()
            } else {
                null
            }
            launcherApps.startShortcut(packageName, shortcutId, null, options, user)
            FileLog.i(TAG, "Launched shortcut $shortcutId for $packageName on display $displayId")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to launch shortcut $shortcutId: ${e.message}")
        }
    }

    private fun categorizeApp(pkg: String): AppCategory = when {
        pkg.contains("map", true) || pkg.contains("navi", true) ||
        pkg.contains("waze", true) || pkg.contains("amap", true) ||
        pkg.contains("gaode", true) -> AppCategory.NAVIGATION

        pkg.contains("music", true) || pkg.contains("spotify", true) ||
        pkg.contains("podcast", true) || pkg.contains("player", true) ||
        pkg.contains("qqmusic", true) || pkg.contains("netease", true) -> AppCategory.MUSIC

        pkg.contains("whatsapp", true) || pkg.contains("telegram", true) ||
        pkg.contains("wechat", true) || pkg.contains("tencent.mm", true) ||
        pkg.contains("messenger", true) || pkg.contains("sms", true) ||
        pkg.contains("dialer", true) || pkg.contains("phone", true) -> AppCategory.COMMUNICATION

        else -> AppCategory.OTHER
    }

    // ─── Cleanup ───

    private fun cleanupSession() {
        vdWaitJob?.cancel()
        vdWaitJob = null
        vdClient?.disconnect()
        vdClient = null
        // Clear stuck pointers from any interrupted touch session
        InputInjectionService.instance?.clearVirtualDisplay()
        videoConnection?.disconnect()
        videoConnection = null
        inputConnection?.disconnect()
        inputConnection = null
        controlConnection?.disconnect()
        controlConnection = null
        activeConnection = null
        _serviceState.value = State.WAITING

        // Force screen on after disconnection. VD server uses SurfaceControl-level
        // display power-off which regular WakeLocks can't reverse. Launch our own
        // activity with FLAG_TURN_SCREEN_ON — WindowManager wakes the display.
        forceWakeScreen()
    }

    /**
     * Multi-layered display wake after disconnection. The VD server shuts off the
     * physical display at the SurfaceControl level (or via cmd display power-off),
     * which puts it in a deeper off state than normal screen timeout. Regular
     * WakeLocks can't recover from this — only system-level mechanisms can.
     *
     * Layers (tried in order, each is independent):
     * 1. PowerManager.wakeUp() via reflection — system-level wake
     * 2. FLAG_TURN_SCREEN_ON activity launch — WindowManager triggers display on
     * 3. WakeLock with ACQUIRE_CAUSES_WAKEUP — framework-level
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun forceWakeScreen() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                FileLog.i(TAG, "Force-waking physical display")
                val pm = getSystemService(POWER_SERVICE) as PowerManager

                // Layer 1: PowerManager.wakeUp() — direct system call
                try {
                    val wakeUp = PowerManager::class.java.getDeclaredMethod(
                        "wakeUp", Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, String::class.java
                    )
                    wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis(),
                        5 /* WAKE_REASON_APPLICATION */, "DiLink:restore")
                    FileLog.i(TAG, "Display wakeUp() succeeded from cleanupSession")
                } catch (e: Exception) {
                    FileLog.d(TAG, "wakeUp() not available from cleanupSession: ${e.message}")
                }

                // Layer 2: Launch MainActivity with FLAG_TURN_SCREEN_ON.
                // WindowManager wakes the display as part of bringing the
                // activity to the foreground, regardless of the display's
                // current power state.
                try {
                    val intent = Intent(this@ConnectionService, Class.forName("com.dilinkauto.client.MainActivity"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.addFlags(0x10000000) // FLAG_TURN_SCREEN_ON
                    startActivity(intent)
                    FileLog.i(TAG, "Launched MainActivity with FLAG_TURN_SCREEN_ON from cleanupSession")
                } catch (e: Exception) {
                    FileLog.d(TAG, "Activity launch for wake failed from cleanupSession: ${e.message}")
                }

                // Layer 3: WakeLock with ACQUIRE_CAUSES_WAKEUP
                @Suppress("DEPRECATION")
                val flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE
                val wl = pm.newWakeLock(flags, "DiLink:display:restore2")
                wl.acquire(3000)
                wl.release()
            } catch (e: Exception) {
                FileLog.w(TAG, "forceWakeScreen error: ${e.message}")
            }
        }
    }

    private fun stopEverything() {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        cleanupSession()
        lastSentIconHash.clear()
        serviceRegistration?.unregister()
        serviceRegistration = null
        _serviceState.value = State.IDLE
    }

    // ─── System ───

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DiLinkAuto::ConnectionService"
        ).apply { acquire(4 * 60 * 60 * 1000L) } // 4h auto-release
    }

    private fun buildNotification(messageRes: Int): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ConnectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ClientApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(messageRes))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_action_stop), stopPi)
            .build()
    }

    private fun updateNotification(messageRes: Int) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(messageRes))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopEverything()
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        packageRemovedReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        packageRemovedReceiver = null
        wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ConnectionService"
        private const val VD_SERVER_PORT = 19637
        const val ACTION_START = "com.dilinkauto.client.START"
        const val ACTION_STOP = "com.dilinkauto.client.STOP"
        const val ACTION_INSTALL_CAR = "com.dilinkauto.client.INSTALL_CAR"
        const val NOTIFICATION_ID = 1001

        private val _serviceState = MutableStateFlow(State.IDLE)
        val serviceState: StateFlow<State> = _serviceState.asStateFlow()

        private val _installStatusStatic = MutableStateFlow("")
        val installStatusFlow: StateFlow<String> = _installStatusStatic.asStateFlow()

        var activeConnection: Connection? = null
            private set
    }
}
