package com.dilinkauto.client.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
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
    private var controlConnection: Connection? = null
    private var videoConnection: Connection? = null
    private var inputConnection: Connection? = null
    private var vdClient: VirtualDisplayClient? = null
    private var pendingAppLaunch: String? = null
    private var vdWaitJob: Job? = null
    private var vdWidth = 1304
    private var vdHeight = 792
    private var targetFps = 30
    private var serviceRegistration: Discovery.ServiceRegistration? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var autoUpdateAttempted = false

    enum class State { IDLE, WAITING, CONNECTED, STREAMING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.rotate() // Archive previous log, start fresh
        // Clear stale static state from previous service instance
        activeConnection = null
        _serviceState.value = State.IDLE
        acquireWakeLock()
        registerNetworkCallback()
        deployAssets()
        UpdateManager.checkForUpdate(force = false)
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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.i(TAG, "Network available: $network")
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
                    // If the active network is still up, our connection is fine
                    if (activeNet != null && activeNet != network) {
                        FileLog.i(TAG, "Network lost: $network (not our active network $activeNet — ignoring)")
                        return
                    }
                }
                FileLog.i(TAG, "Network lost: $network — affects our connection")
                resetConnectionForNetworkChange()
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
        val resp = HandshakeResponse(
            accepted = true,
            deviceName = android.os.Build.MODEL,
            displayWidth = request.screenWidth,
            displayHeight = request.screenHeight,
            virtualDisplayId = -1,
            adbPort = 5555,
            vdServerJarPath = vdJarPath
        )
        serviceScope.launch(Dispatchers.IO) {
            // Check version BEFORE sending response — determines the flow
            val carVersion = request.appVersionCode
            @Suppress("DEPRECATION")
            val myVersion = packageManager.getPackageInfo(packageName, 0).versionCode
            val needsUpdate = carVersion < myVersion && !autoUpdateAttempted

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
                FileLog.i(TAG, "Car app outdated (v$carVersion < v$myVersion) — updating, then waiting for reconnect")
                _installStatusStatic.value = "Updating car app (v$carVersion → v$myVersion)..."
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

                if (carVersion < myVersion) {
                    FileLog.i(TAG, "Car app outdated (v$carVersion) — update already attempted this session, proceeding")
                } else {
                    FileLog.i(TAG, "Car app up-to-date (v$carVersion)")
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
                _installStatusStatic.value = "Connecting to car ADB..."
                val privKey = java.io.File(filesDir, "adbkey")
                val pubKey = java.io.File(filesDir, "adbkey.pub")
                if (!privKey.exists()) {
                    filesDir.mkdirs()
                    AdbKeyPair.generate(privKey, pubKey)
                }
                val keyPair = AdbKeyPair.read(privKey, pubKey)
                val dadb = Dadb.create(carIp, 5555, keyPair)

                try {
                    _installStatusStatic.value = "Pushing car APK..."
                    val remotePath = "/data/local/tmp/app-server.apk"
                    dadb.push(apkFile, remotePath)
                    _installStatusStatic.value = "Installing car app..."
                    val result = dadb.shell("pm install -r $remotePath").allOutput
                    FileLog.i(TAG, "Auto-update result: ${result.trim()}")
                    if (result.contains("Success")) {
                        _installStatusStatic.value = "Car app updated! Restarting..."
                        FileLog.i(TAG, "Car app auto-updated — restarting")
                        dadb.shell("am start --activity-clear-task -n com.dilinkauto.server/.MainActivity")
                    } else {
                        _installStatusStatic.value = "Update failed: ${result.trim()}"
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatusStatic.value = "Auto-update failed: ${e.message}"
                FileLog.e(TAG, "Auto-update failed: ${e.message}")
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

        try {
            // Batched MOVE: all pointers in one message
            if (frame.messageType == InputMsg.TOUCH_MOVE_BATCH) {
                val batch = TouchMoveBatch.decode(frame.payload)
                for (p in batch.pointers) {
                    InputInjectionService.instance?.injectTouch(TouchEvent(
                        action = InputMsg.TOUCH_MOVE,
                        pointerId = p.pointerId,
                        x = p.x, y = p.y,
                        pressure = p.pressure,
                        timestamp = p.timestamp
                    ))
                }
                return
            }

            val event = TouchEvent.decode(frame.payload)
            InputInjectionService.instance?.injectTouch(event)
        } catch (e: Exception) {
            FileLog.e(TAG, "handleInputFrame error type=0x${frame.messageType.toString(16)}: ${e.message}", e)
        }
    }

    // ─── Car App Install via ADB ───

    private val _installStatus get() = _installStatusStatic

    fun installCarApp(explicitIp: String? = null) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ensureAssetsReady()
                val apkFile = java.io.File(filesDir, "app-server.apk")
                if (!apkFile.exists()) {
                    _installStatus.value = "Car APK not found"
                    FileLog.w(TAG, "No embedded car APK")
                    return@launch
                }

                _installStatus.value = if (explicitIp != null) "Connecting to $explicitIp..." else "Searching for car..."
                val carIp = if (!explicitIp.isNullOrBlank()) {
                    if (probePort(explicitIp, 5555)) explicitIp else {
                        _installStatus.value = "$explicitIp not reachable on port 5555"
                        null
                    }
                } else findCarAdb()
                if (carIp == null) {
                    _installStatus.value = "Car not found. Plug into USB or check WiFi ADB."
                    FileLog.w(TAG, "Could not find car ADB on USB or network")
                    return@launch
                }

                _installStatus.value = "Connecting to $carIp..."
                FileLog.i(TAG, "Connecting to car ADB at $carIp:5555")
                val privKey = java.io.File(filesDir, "adbkey")
                val pubKey = java.io.File(filesDir, "adbkey.pub")
                if (!privKey.exists()) {
                    filesDir.mkdirs()
                    AdbKeyPair.generate(privKey, pubKey)
                }
                val keyPair = AdbKeyPair.read(privKey, pubKey)
                val dadb = Dadb.create(carIp, 5555, keyPair)

                try {
                    _installStatus.value = "Checking version..."
                    val versionOutput = dadb.shell(
                        "dumpsys package com.dilinkauto.server 2>/dev/null | grep versionCode"
                    ).allOutput
                    val installedVersion = Regex("""versionCode=(\d+)""")
                        .find(versionOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    @Suppress("DEPRECATION")
                    val myVersion = packageManager.getPackageInfo(packageName, 0).versionCode
                    FileLog.i(TAG, "Car app: installed=v$installedVersion, embedded=v$myVersion")

                    if (installedVersion >= myVersion) {
                        _installStatus.value = "Already up-to-date (v$installedVersion)"
                        return@launch
                    }

                    _installStatus.value = "Pushing APK (${apkFile.length() / 1024 / 1024}MB)..."
                    val remotePath = "/data/local/tmp/app-server.apk"
                    dadb.push(apkFile, remotePath)
                    FileLog.i(TAG, "Car APK pushed (${apkFile.length()} bytes)")

                    _installStatus.value = "Installing v$myVersion..."
                    val result = dadb.shell("pm install -r $remotePath").allOutput
                    FileLog.i(TAG, "Install result: ${result.trim()}")

                    if (result.contains("Success")) {
                        _installStatus.value = "Launching car app..."
                        dadb.shell("am start --activity-clear-task -n com.dilinkauto.server/.MainActivity")
                        _installStatus.value = "Car app v$myVersion installed!"
                    } else {
                        _installStatus.value = "Failed: ${result.trim()}"
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatus.value = "Error: ${e.message}"
                FileLog.e(TAG, "Car app install failed", e)
            } finally {
                delay(5000)
                _installStatus.value = ""
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

    private fun sendAppList() {
        val conn = controlConnection ?: return
        val pm = packageManager

        serviceScope.launch(Dispatchers.IO) {
            try {
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { info ->
                    val iconPng = try {
                        val drawable = info.loadIcon(pm)
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            96, 96, android.graphics.Bitmap.Config.RGB_565
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, 96, 96)
                        drawable.draw(canvas)
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        stream.toByteArray()
                    } catch (e: Exception) { ByteArray(0) }

                    AppInfo(
                        info.activityInfo.packageName,
                        info.loadLabel(pm).toString(),
                        categorizeApp(info.activityInfo.packageName),
                        iconPng
                    )
                }.sortedBy { it.category.id }

                conn.sendData(DataMsg.APP_LIST, AppListMessage(apps).encode())
                val commApps = apps.filter { it.category == AppCategory.COMMUNICATION }.map { it.packageName }
                FileLog.i(TAG, "App list sent: ${apps.size} apps (comm=${commApps.size}: ${commApps.joinToString()})")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send app list", e)
            }
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
        videoConnection?.disconnect()
        videoConnection = null
        inputConnection?.disconnect()
        inputConnection = null
        controlConnection?.disconnect()
        controlConnection = null
        activeConnection = null
        autoUpdateAttempted = false
        _serviceState.value = State.WAITING
    }

    private fun stopEverything() {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        cleanupSession()
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
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
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
