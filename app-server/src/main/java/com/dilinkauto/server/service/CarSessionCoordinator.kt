package com.dilinkauto.server.service

import android.content.Context
import android.view.Surface
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.adb.WifiGatewayProbe
import com.dilinkauto.server.decoder.AudioPlayer
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 负责多通道（9636/9638/9639/9640）连接生命周期、握手与视口/黑屏自愈重握手编排、
 * 以及连接状态机转换（从 [CarConnectionService] 剥离以遵循 SRP）。
 */
internal class CarSessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val carPrefs: CarPrefs,
    private val videoDecoder: VideoDecoder,
    private val audioPlayer: AudioPlayer,
    private val touchSender: CarTouchSender,
    private val logWriter: CarLogWriter,
    private val appListReceiver: CarAppListReceiver,
    private val adbTrackerProvider: () -> CarAdbTracker,
    private val deployVdServer: () -> Unit,
    private val updateNotification: (Int) -> Unit,
    /**
     * 手机电量状态回调（issue #151）：收到合法 `PHONE_STATUS` 帧时传值，会话断开时传
     * `null`。由 [CarConnectionService] 接到 [PhoneStatusProvider] 上，作为该
     * StateFlow 的唯一写入路径。
     */
    private val onPhoneStatus: (PhoneStatus?) -> Unit,
) {
    private val adbTracker: CarAdbTracker get() = adbTrackerProvider()

    @Volatile internal var controlConnection: Connection? = null
    @Volatile internal var videoConnection: Connection? = null
    @Volatile internal var inputConnection: Connection? = null
    @Volatile internal var audioConnection: Connection? = null

    @Volatile var phoneHost: String? = null
        internal set

    private var consecutiveFailures = 0

    // ─── Handshake & Viewport parameters ───
    var handshakeVdDpi: Int = VideoConfig.VIRTUAL_DISPLAY_DPI
        internal set
    var vdServerJarPath: String = VdDeploy.JAR_PATH
        internal set
    var vdWidth: Int = 1408
        internal set
    var vdHeight: Int = 792
        internal set
    var targetFps: Int = VideoConfig.TARGET_FPS
        internal set

    fun setStartupFps(value: Int) {
        carPrefs.startupFps = value
        targetFps = value
    }

    // ─── Prerequisites ───
    @Volatile var wifiReady: Boolean = false
        internal set
    @Volatile var vdServerStarted: Boolean = false
        internal set
    @Volatile var shizukuMode: Boolean = false
        internal set
    @Volatile var handshakeDone: Boolean = false
        internal set
    @Volatile var reHandshakeInFlight: Boolean = false
        internal set

    private var connectionScope: Job? = null
    private var discoveryJob: Job? = null
    private var connectJob: Job? = null

    private val teardownGuard = AtomicBoolean(false)
    private var blackScreenRebuilds = 0
    @Volatile private var blackScreenRecoveryInFlight = false
    private var videoFrameCount = 0L

    val lastSyncFrameRequestMs = AtomicLong(0L)

    val decoderExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "CarDecoderLifecycle").apply { isDaemon = true }
        }

    val audioExecutor: ExecutorService = newAudioExecutor()

    // ─── State Flows ───
    private val _state = MutableStateFlow(CarConnectionService.State.IDLE)
    val state: StateFlow<CarConnectionService.State> = _state.asStateFlow()

    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    private val _phoneAudioSupported = MutableStateFlow(false)
    val phoneAudioSupported: StateFlow<Boolean> = _phoneAudioSupported.asStateFlow()

    private val _vdStackEmpty = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vdStackEmpty: SharedFlow<Unit> = _vdStackEmpty.asSharedFlow()

    private val _videoReady = MutableStateFlow(false)
    val videoReady: StateFlow<Boolean> = _videoReady.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState.asStateFlow()

    private val viewportGate = ViewportRehandshakeGate(
        debounceMs = VIEWPORT_REHANDSHAKE_DEBOUNCE_MS,
        schedule = { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ViewportRehandshakeGate.Cancelable { job.cancel() }
        },
        request = { target ->
            val ctrl = controlConnection
            if (ctrl == null || !ctrl.isConnected) {
                carLogSend("Viewport re-handshake skipped: control link gone", "W")
            } else {
                carLogSend("Car viewport changed -> re-handshake ${target.width}x${target.height} (was ${vdWidth}x${vdHeight})")
                scope.launch(Dispatchers.IO) { rehandshakeOnExistingControl(ctrl, target.width, target.height, target.dpi) }
            }
        },
        isInFlight = { reHandshakeInFlight },
    )

    fun setStatusMessage(resId: Int, vararg formatArgs: Any) {
        _statusMessage.value = if (formatArgs.isEmpty()) context.getString(resId) else context.getString(resId, *formatArgs)
    }

    private fun carLogSend(msg: String, level: String = "I") = logWriter.send(msg, level)

    // ─── Connection Orchestration ───

    fun startConnection() {
        if (_state.value == CarConnectionService.State.STREAMING || _state.value == CarConnectionService.State.CONNECTED) return

        connectionScope?.cancel()
        connectJob?.cancel()
        connectJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        disconnectAllConnections()

        teardownGuard.set(false)
        blackScreenRebuilds = 0
        viewportGate.reset()

        wifiReady = false
        vdServerStarted = false
        shizukuMode = false
        handshakeDone = false
        _videoReady.value = false
        adbTracker.resetAdbReadiness()

        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)
        logWriter.beginSession()
        carPrefs.userDisconnected = false
        _state.value = CarConnectionService.State.CONNECTING
        _statusMessage.value = context.getString(R.string.status_connecting)
        updateNotification(R.string.notification_searching)

        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            startWifiTrack()
            adbTracker.startUsbTrack(trackScope())
        }
    }

    fun beginConnect(host: String, port: Int) {
        connectionScope?.cancel()
        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            adbTracker.startUsbTrack(trackScope())
        }
        connectToPhone(host, port)
    }

    fun connectManual(host: String, port: Int = Ports.DEFAULT_PORT) {
        carPrefs.userDisconnected = false
        _state.value = CarConnectionService.State.CONNECTING
        beginConnect(host, port)
    }

    fun trackScope(): CoroutineScope =
        connectionScope?.let { CoroutineScope(it + Dispatchers.Main.immediate) } ?: scope

    @Synchronized
    fun checkAndAdvance() {
        val currentState = _state.value
        val usbReady = adbTracker.usbReady
        carLogSend("checkAndAdvance: state=$currentState wifi=$wifiReady usb=$usbReady vd=$vdServerStarted video=${_videoReady.value}")

        when (currentState) {
            CarConnectionService.State.IDLE -> { /* waiting for user action */ }

            CarConnectionService.State.CONNECTING -> {
                if (wifiReady && (usbReady || shizukuMode)) {
                    carLogSend("All connections ready — connected")
                    _state.value = CarConnectionService.State.CONNECTED
                    _statusMessage.value = context.getString(R.string.status_waiting_video)
                } else if (wifiReady && !usbReady && !shizukuMode) {
                    _statusMessage.value = context.getString(R.string.status_waiting_usb)
                } else if (!wifiReady && usbReady) {
                    _statusMessage.value = context.getString(R.string.status_waiting_wifi)
                } else if (!wifiReady && !usbReady) {
                    _statusMessage.value = context.getString(R.string.status_connecting)
                }
            }

            CarConnectionService.State.CONNECTED -> {
                if (_videoReady.value) {
                    carLogSend("Video ready — streaming")
                    _state.value = CarConnectionService.State.STREAMING
                    consecutiveFailures = 0
                }
            }

            CarConnectionService.State.STREAMING -> { /* running */ }
        }
    }

    fun startWifiTrack() {
        trackScope().launch(Dispatchers.IO) {
            delay(500)
            while (isActive && !wifiReady && !handshakeDone && _state.value == CarConnectionService.State.CONNECTING) {
                val gatewayIp = getWifiGatewayIp()
                if (gatewayIp != null) {
                    carLogSend("WiFi track: trying gateway $gatewayIp")
                    connectToPhone(gatewayIp, Ports.DEFAULT_PORT)
                }
                delay(3000)
            }
        }

        discoveryJob?.cancel()
        discoveryJob = trackScope().launch {
            Discovery.discoverServices(context).collect { service ->
                if (!wifiReady && !handshakeDone && _state.value == CarConnectionService.State.CONNECTING) {
                    carLogSend("WiFi track: mDNS found ${service.host}")
                    connectToPhone(service.host, service.port)
                }
            }
        }
    }

    private fun getWifiGatewayIp(): String? {
        if (carPrefs.devMode) {
            val devIp = carPrefs.devPhoneIp
            if (!devIp.isNullOrBlank()) return devIp
        }
        return WifiGatewayProbe.gatewayIp(context)
    }

    fun connectToPhone(host: String, port: Int) {
        if (handshakeDone && controlConnection?.isConnected == true) {
            carLogSend("connectToPhone: session active, skipping ($host:$port)")
            return
        }
        if (reHandshakeInFlight) {
            carLogSend("connectToPhone: re-handshake in flight, skipping ($host:$port)")
            return
        }

        connectJob?.cancel()
        disconnectAllConnections()

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val ctrl = Connection.connect(host, port, scope)
                controlConnection = ctrl
                phoneHost = host

                ctrl.onLog { msg -> carLogSend("ControlConn: $msg") }
                ctrl.onDisconnect { scope.launch { handleDisconnect() } }
                ctrl.onFrames(Channel.CONTROL) { handleControlFrame(it) }
                ctrl.onFrames(Channel.DATA) { handleDataFrame(it) }

                ctrl.start()
                carLogSend("Control connected to $host:$port — sending handshake")

                val displayMetrics = context.resources.displayMetrics
                val vp = CarViewport.size(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density)
                val viewportWidth = vp.first
                val viewportHeight = vp.second
                sendHandshake(ctrl, viewportWidth, viewportHeight, displayMetrics.densityDpi)
                handshakeDone = true

                withContext(Dispatchers.Main) { updateNotification(R.string.notification_connected) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("WiFi connect failed ($host:$port): ${e.message}")
                if (handshakeDone) {
                    handleDisconnect()
                } else {
                    disconnectAllConnections(clearListeners = true)
                    connectJob = null
                }
            }
        }
    }

    private fun sendHandshake(ctrl: Connection, widthPx: Int, heightPx: Int, dpi: Int) {
        val handshake = buildHandshakeRequest(
            context = context,
            screenWidth = widthPx,
            screenHeight = heightPx,
            screenDpi = dpi,
            targetFps = targetFps,
            dpiOverride = carPrefs.startupDpi,
            bitrate = carPrefs.startupBitrate,
            audioEnabled = carPrefs.audioEnabled
        )
        ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, handshake.encode())
    }

    private fun connectVideoAndInput(host: String) {
        scope.launch(Dispatchers.IO) {
            try {
                carLogSend("Connecting video (${Ports.VIDEO_PORT}) and input (${Ports.INPUT_PORT})...")

                val videoDef = async { Connection.connect(host, Ports.VIDEO_PORT, scope) }
                val inputDef = async { Connection.connect(host, Ports.INPUT_PORT, scope) }

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

    private fun connectAudioIfEnabled(host: String) {
        if (!carShouldConnectAudio(carPrefs.audioEnabled, _phoneAudioSupported.value)) {
            carLogSend(
                "Audio: not connecting — enabled=${carPrefs.audioEnabled} " +
                    "phoneSupported=${_phoneAudioSupported.value}"
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                carLogSend("Audio: connecting to ${Ports.AUDIO_PORT}...")
                val audio = Connection.connect(host, Ports.AUDIO_PORT, scope)
                audioConnection = audio
                audio.onLog { msg -> carLogSend("AudioConn: $msg") }
                audio.onDisconnect {
                    carLogSend("Audio: connection lost — stopping playback (video unaffected)", "W")
                    if (audioConnection === audio) {
                        submitAudioLifecycle(audioExecutor) { audioPlayer.stop() }
                    }
                }
                audio.onFrames(Channel.AUDIO) { handleAudioFrame(it) }
                audio.start(enableHeartbeat = false)
                submitAudioLifecycle(audioExecutor) {
                    if (audio.isConnected) {
                        audioPlayer.start()
                        carLogSend("Audio: connection established — playback started")
                    } else {
                        carLogSend("Audio: 9640 dropped before playback started — not starting", "W")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("Audio: connect failed (video unaffected): ${e.message}", "W")
                submitAudioLifecycle(audioExecutor) { audioPlayer.stop() }
                audioConnection = null
            }
        }
    }

    private fun handleAudioFrame(frame: FrameCodec.Frame) {
        try {
            when (frame.messageType) {
                AudioMsg.CONFIG -> audioPlayer.configure(AudioPayload.decodeConfig(frame.payload))
                AudioMsg.FRAME -> audioPlayer.onFrame(AudioPayload.decodeFrame(frame.payload))
                else -> carLogSend("Audio: unknown messageType=0x${Integer.toHexString(frame.messageType.toInt() and 0xFF)}", "W")
            }
        } catch (e: Exception) {
            carLogSend("Audio: frame decode failed: ${e.message}", "W")
        }
    }

    private fun handleVideoFrame(frame: FrameCodec.Frame) {
        val isConfig = frame.messageType == VideoMsg.CONFIG
        videoFrameCount++
        if (isConfig || videoFrameCount % 60 == 0L) {
            carLogSend("Video ${if (isConfig) "CONFIG" else "FRAME"} size=${frame.payload.size} total=$videoFrameCount")
        }

        if (!_videoReady.value && !isConfig) {
            _videoReady.value = true
            carLogSend("First video frame — VD stream ready")
            checkAndAdvance()
        }
        videoDecoder.onFrameReceived(isConfig, frame.payload)
    }

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_RESPONSE -> {
                val response = HandshakeResponse.decode(frame.payload)
                carLogSend("Handshake OK: ${response.deviceName} jarPath=${response.vdServerJarPath} connMethod=${response.connectionMethod} vdDpi=${response.vdDpi}")
                _phoneName.value = response.deviceName
                _phoneAudioSupported.value = phoneSupportsAudio(response.supportedFeatures)
                carLogSend("Peer features=0x${response.supportedFeatures.toString(16)} audioSupported=${_phoneAudioSupported.value}")

                vdWidth = sanitizePeerDimension(response.displayWidth, vdWidth)
                vdHeight = sanitizePeerDimension(response.displayHeight, vdHeight)
                handshakeVdDpi = response.vdDpi
                if (response.vdServerJarPath.isNotEmpty()) {
                    vdServerJarPath = response.vdServerJarPath
                }

                handshakeDone = true
                if (response.connectionMethod == CONNECTION_METHOD_SHIZUKU) {
                    shizukuMode = true
                    carLogSend("Shizuku mode — phone will deploy VD server, waiting for VD_PORTS_BOUND")
                } else {
                    if (adbTracker.isAdbAvailable() && !vdServerStarted) {
                        carLogSend("ADB available at handshake — deploying VD server")
                        deployVdServer()
                    } else if (!vdServerStarted) {
                        carLogSend("Handshake OK — attempting deploy/fallback to phone")
                        deployVdServer()
                    }
                }
            }
            ControlMsg.VD_PORTS_BOUND -> {
                carLogSend("VD ports bound — connecting video and input directly to VD server")
                val host = phoneHost
                if (host != null) {
                    connectVideoAndInput(host)
                    connectAudioIfEnabled(host)
                } else {
                    carLogSend("ERROR: phoneHost is null when VD_PORTS_BOUND received")
                }
            }
            ControlMsg.APP_STARTED -> {}
            ControlMsg.VD_STACK_EMPTY -> {
                carLogSend("VD stack empty — switching to home")
                _vdStackEmpty.tryEmit(Unit)
            }
        }
    }

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.APP_LIST -> appListReceiver.onAppList(frame.payload, scope)
            DataMsg.MEDIA_METADATA -> { _mediaMetadata.value = MediaMetadata.decode(frame.payload) }
            DataMsg.MEDIA_PLAYBACK_STATE -> { _playbackState.value = PlaybackState.decode(frame.payload) }
            DataMsg.APP_UNINSTALLED -> appListReceiver.onAppUninstalled(frame.payload)
            DataMsg.APP_INFO_DATA -> appListReceiver.onAppInfoData(frame.payload)
            DataMsg.PHONE_STATUS -> {
                // issue #151：手机电量。坏帧只丢这一条并留痕，绝不能把 data reader 打断
                // （与 docs/protocol.md 的「Decoding contract」一致）。
                try {
                    val status = PhoneStatus.decode(frame.payload)
                    carLogSend("phone status level=${status.level} charging=${status.charging} temp=${status.temperatureC}")
                    onPhoneStatus(status)
                } catch (e: ProtocolDecodeException) {
                    carLogSend("Malformed PHONE_STATUS ignored: ${e.message}", "W")
                }
            }
            DataMsg.LOG_TOGGLE -> {
                val enabled = frame.payload.isNotEmpty() && frame.payload[0].toInt() == 1
                logWriter.setEnabled(enabled)
                videoDecoder.debugFrameStats = enabled
                carLogSend("Logging ${if (enabled) "enabled" else "disabled"} by phone")
            }
        }
    }

    fun onCarViewportChanged(widthPx: Int, heightPx: Int, dpi: Int) {
        val dm = context.resources.displayMetrics
        val vp = CarViewport.size(widthPx, heightPx, dm.density)
        val st = _state.value
        val sessionReady = st == CarConnectionService.State.STREAMING || st == CarConnectionService.State.CONNECTED ||
            (st == CarConnectionService.State.CONNECTING && reHandshakeInFlight)
        viewportGate.onViewportChanged(
            candidate = ViewportRehandshakeGate.Target(vp.first, vp.second, dpi),
            appliedWidth = vdWidth,
            appliedHeight = vdHeight,
            sessionReady = sessionReady,
        )
    }

    suspend fun rehandshakeForBlackScreen() {
        val ctrl = controlConnection
        if (ctrl == null || !ctrl.isConnected) {
            carLogSend("[BLACK] control link gone, cannot re-handshake", "W")
            return
        }
        if (_state.value != CarConnectionService.State.STREAMING && _state.value != CarConnectionService.State.CONNECTED) return
        carLogSend("[BLACK] re-handshake at current viewport ${vdWidth}x$vdHeight")
        rehandshakeOnExistingControl(ctrl, vdWidth, vdHeight, carPrefs.startupDpi)
    }

    fun handleSustainedBlackScreen() {
        val sessionAlive = (_state.value == CarConnectionService.State.STREAMING || _state.value == CarConnectionService.State.CONNECTED) &&
            handshakeDone
        if (sessionAlive && !reHandshakeInFlight &&
            blackScreenRebuilds < CarConnectionService.MAX_BLACK_SCREEN_REBUILDS && !blackScreenRecoveryInFlight
        ) {
            blackScreenRebuilds++
            blackScreenRecoveryInFlight = true
            carLogSend("[BLACK] requesting VD rebuild via re-handshake (rebuild $blackScreenRebuilds/${CarConnectionService.MAX_BLACK_SCREEN_REBUILDS} this session)", "W")
            scope.launch(Dispatchers.IO) {
                try { rehandshakeForBlackScreen() } catch (e: Exception) {
                    carLogSend("[BLACK] rebuild failed: ${e.message}", "E")
                } finally { blackScreenRecoveryInFlight = false }
            }
        }
    }

    private suspend fun rehandshakeOnExistingControl(
        ctrl: Connection,
        newVpW: Int,
        newVpH: Int,
        dpi: Int
    ) {
        reHandshakeInFlight = true
        try {
            rehandshakeOnExistingControlLocked(ctrl, newVpW, newVpH, dpi)
        } finally {
            reHandshakeInFlight = false
            viewportGate.onRehandshakeFinished()
        }
    }

    internal suspend fun rehandshakeOnExistingControlLocked(
        ctrl: Connection,
        newVpW: Int,
        newVpH: Int,
        dpi: Int
    ) {
        connectionScope?.cancel()
        connectJob?.cancel()
        connectJob = null
        discoveryJob?.cancel()
        discoveryJob = null

        videoConnection?.clearDisconnectListener()
        inputConnection?.clearDisconnectListener()
        audioConnection?.clearDisconnectListener()
        videoConnection?.disconnect(); videoConnection = null
        inputConnection?.disconnect(); inputConnection = null

        val audio = audioConnection
        audioConnection = null
        submitAudioTeardown(audioExecutor, stop = { audioPlayer.stop() }, disconnect = { audio?.disconnect() })

        videoDecoder.resetBlackScreenState()

        _videoReady.value = false
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        vdWidth = newVpW
        vdHeight = newVpH
        _state.value = CarConnectionService.State.CONNECTING
        _statusMessage.value = context.getString(R.string.status_starting_vd)

        try {
            sendHandshake(ctrl, newVpW, newVpH, dpi)
        } catch (e: Exception) {
            carLogSend("Re-handshake send failed: ${e.message}")
            handleDisconnect()
        }
    }

    fun disconnectAllConnections(clearListeners: Boolean = false) {
        if (clearListeners) {
            videoConnection?.clearDisconnectListener()
            inputConnection?.clearDisconnectListener()
            controlConnection?.clearDisconnectListener()
            audioConnection?.clearDisconnectListener()
        }
        videoConnection?.disconnect()
        videoConnection = null
        inputConnection?.disconnect()
        inputConnection = null
        controlConnection?.disconnect()
        controlConnection = null

        val audio = audioConnection
        audioConnection = null
        submitAudioTeardown(audioExecutor, stop = { audioPlayer.stop() }, disconnect = { audio?.disconnect() })
    }

    fun handleDisconnect() {
        if (!teardownGuard.compareAndSet(false, true)) {
            carLogSend("handleDisconnect: already tearing down this generation — ignoring re-entry", "W")
            return
        }
        carLogSend("handleDisconnect — state=${_state.value} usb=${adbTracker.usbReady} wifi=${wifiReady}")
        connectionScope?.cancel()
        connectionScope = null
        connectJob?.cancel()
        connectJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        reHandshakeInFlight = false
        viewportGate.reset()

        stopDecoderAsync()
        appListReceiver.reset()

        if (adbTracker.adbController?.isConnected != true) {
            adbTracker.adbController?.disconnect()
            adbTracker.adbController = null
        }
        disconnectAllConnections(clearListeners = true)

        _phoneName.value = ""
        _videoReady.value = false
        videoFrameCount = 0
        touchSender.resetCounters()
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        // issue #151：电量属于会话态，断开后必须回到 `—`，否则会显示上一台手机的旧值。
        onPhoneStatus(null)
        adbTracker.resetAdbReadiness()
        if (adbTracker.adbController?.isConnected == true) adbTracker.usbReady = true

        if (carPrefs.userDisconnected) {
            _state.value = CarConnectionService.State.IDLE
            adbTracker.usbReady = false
            carLogSend("User disconnect — idle (persisted)")
            scope.launch { updateNotification(R.string.notification_searching) }
            return
        }

        _state.value = CarConnectionService.State.IDLE

        if (adbTracker.noAdbCount >= 3) {
            _statusMessage.value = context.getString(R.string.status_no_adb)
            carLogSend("No ADB after ${adbTracker.noAdbCount} attempts — stopping reconnect. Plug phone into car USB.")
            scope.launch { updateNotification(R.string.plug_phone_usb) }
            return
        }

        carLogSend("Connection lost — will reconnect")

        scope.launch {
            consecutiveFailures++
            val backoffMs = CarConnectionService.reconnectBackoffMs(consecutiveFailures)
            carLogSend("Reconnect backoff: ${backoffMs}ms (failures=$consecutiveFailures)")
            delay(backoffMs)
            if (_state.value == CarConnectionService.State.IDLE && !carPrefs.userDisconnected) {
                startConnection()
            }
        }
    }

    fun disconnectFromPhone() {
        carLogSend("User disconnect — will not auto-reconnect")
        carPrefs.userDisconnected = true
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.DISCONNECT) } catch (_: Exception) {}
            disconnectAllConnections()
        }
    }

    fun sendCommandToVd(msgType: Byte, payload: ByteArray = ByteArray(0)) {
        scope.launch(Dispatchers.IO) {
            val tag = "sendCommandToVd 0x${Integer.toHexString(msgType.toInt() and 0xFF)}"
            try {
                val conn = inputConnection
                if (conn == null) {
                    carLogSend("$tag dropped: inputConnection is null (VD server not connected?)", "W")
                    return@launch
                }
                conn.sendControl(msgType, payload)
                carLogSend("$tag sent (${payload.size}B)")
            } catch (e: Exception) {
                carLogSend("$tag failed: ${e.message}", "E")
            }
        }
    }

    fun launchApp(packageName: String) {
        sendCommandToVd(ControlMsg.LAUNCH_APP, LaunchAppMessage(packageName).encode())
    }

    fun goRecent() {
        sendCommandToVd(ControlMsg.GO_RECENT)
    }

    fun goHome() {
        sendCommandToVd(ControlMsg.GO_HOME)
    }

    fun goBack() {
        sendCommandToVd(ControlMsg.GO_BACK)
    }

    fun requestUninstall(packageName: String) {
        sendCommandToVd(ControlMsg.APP_UNINSTALL, packageName.toByteArray(Charsets.UTF_8))
        carLogSend("Requested uninstall: $packageName")
    }

    fun requestAppInfo(packageName: String) {
        sendCommandToVd(ControlMsg.APP_INFO, packageName.toByteArray(Charsets.UTF_8))
        carLogSend("Requested app info: $packageName")
    }

    fun sendMediaAction(action: MediaAction) {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendData(DataMsg.MEDIA_ACTION, byteArrayOf(action.id)) }
            catch (e: Exception) { carLogSend("mediaAction failed: ${e.message}", "E") }
        }
    }

    fun onMirrorSurfaceCreated(surface: Surface) {
        decoderExecutor.execute {
            carLogSend("[MirrorScreen] SurfaceView surface created, decoder.isRunning=${videoDecoder.isRunning}")
            if (videoDecoder.isRunning) {
                videoDecoder.switchSurface(surface)
                carLogSend("[MirrorScreen] Decoder surface switched to SurfaceView (no restart)")
            } else {
                videoDecoder.start(surface, vdWidth, vdHeight, targetFps)
                carLogSend("[MirrorScreen] Decoder started on SurfaceView surface")
            }
        }
    }

    fun onMirrorSurfaceDestroyed() {
        videoDecoder.invalidateSurface()
        carLogSend("[MirrorScreen] SurfaceView surface destroyed — decoder stays running, render gated off")
    }

    fun stopDecoderAsync() {
        if (!videoDecoder.isRunning) return
        try {
            decoderExecutor.execute { videoDecoder.stop() }
        } catch (_: Exception) {
            videoDecoder.stop()
        }
    }

    fun shutdown() {
        stopDecoderAsync()
        disconnectAllConnections(clearListeners = true)
        phoneHost = null
        _state.value = CarConnectionService.State.IDLE
        decoderExecutor.shutdown()
        audioExecutor.shutdown()
    }

    fun sanitizePeerDimension(value: Int, fallback: Int): Int =
        Companion.sanitizePeerDimension(value, fallback)

    companion object {
        const val VIEWPORT_REHANDSHAKE_DEBOUNCE_MS = 700L

        fun sanitizePeerDimension(value: Int, fallback: Int): Int =
            value.takeIf { it in CarConnectionService.MIN_PEER_DIMENSION..CarConnectionService.MAX_PEER_DIMENSION } ?: fallback
    }
}
