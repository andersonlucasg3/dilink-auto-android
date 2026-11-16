package com.dilinkauto.server.service

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.adb.WifiGatewayProbe
import com.dilinkauto.server.decoder.AudioPlayer
import com.dilinkauto.server.decoder.RenderPipelinePolicy
import com.dilinkauto.server.decoder.SurfaceWaitLogger
import com.dilinkauto.server.decoder.VideoDecoder
import com.dilinkauto.server.probe.BydVehicleProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * 「等 Surface / 等首帧」诊断节奏（issue #198）：状态停在 CONNECTED 时每 2s 一行，
     * 实车回签时能直接区分「SurfaceView 不可见、没有 Surface」与「有 Surface 但没出帧」。
     */
    private val surfaceWait = SurfaceWaitLogger()

    /**
     * IDR 补帧请求闸门状态（issue #75 / #163）：与手机端共用 protocol-core 的 [SyncRequestGate]，
     * 滑动窗口「1s 内最多 2 次」。用 [AtomicReference] 承载不可变状态，靠 CAS 保证并发下不超发。
     */
    val syncRequestGate = AtomicReference(SyncRequestGate())

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

    /**
     * 解码器实测入帧帧率（issue #203），0 = 还没有样本。
     *
     * 由 [VideoDecoder] 每喂满 30 帧回调一次（墙钟口径），只用于设置页展示实际值 ——
     * 既不禁用任何档位，也不参与任何调度判定。
     */
    private val _measuredFps = MutableStateFlow(0.0)
    val measuredFps: StateFlow<Double> = _measuredFps.asStateFlow()

    /**
     * 手机端回传的**实际生效** VD 密度与它对应的视口上限（issue #202 评审第 2 条）。
     *
     * 只读现有握手响应（`HandshakeResponse.vdDpi` + displayWidth/displayHeight），
     * 不新增协议字段。设置页用它提示"填的 480 实际按多少生效"，否则用户会以为
     * 越界回落没生效。0 = 还没握手成功（UI 退化为静态提示）。
     */
    private val _effectiveVdDpi = MutableStateFlow(0)
    val effectiveVdDpi: StateFlow<Int> = _effectiveVdDpi.asStateFlow()
    private val _effectiveVdSafeMaxDpi = MutableStateFlow(0)
    val effectiveVdSafeMaxDpi: StateFlow<Int> = _effectiveVdSafeMaxDpi.asStateFlow()

    /** [VideoDecoder.onMeasuredFps] 的投递口（喂帧线程调用，只写 StateFlow）。 */
    fun onMeasuredFps(fps: Double) {
        _measuredFps.value = fps
    }

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

    /**
     * 用户最近一次主动断开的时刻（`elapsedRealtime`，0 = 本进程内没有断开记录）。
     *
     * 只用于冷却期兜底（见 [AutoReconnectPolicy.USER_DISCONNECT_COOLDOWN_MS]）：真正的
     * 抑制依据是持久化的 [CarPrefs.userDisconnected]，进程重启后仍有效。
     */
    @Volatile private var lastUserDisconnectAtMs = 0L

    private fun inUserDisconnectCooldown(): Boolean {
        val at = lastUserDisconnectAtMs
        if (at <= 0L) return false
        return SystemClock.elapsedRealtime() - at < AutoReconnectPolicy.USER_DISCONNECT_COOLDOWN_MS
    }

    /**
     * 发起连接的**唯一**闸门（issue #201）：所有入口（服务 START / ADB 就绪 / 重试回环 /
     * 网络恢复 / WiFi 探测 / 用户点重连）都必须先过这里。
     *
     * 命中抑制时用 [CarLogWriter.sendUnconditional] 打一行 —— 此刻控制链路已断，普通
     * `send()` 会被 `enabled` 与离线缓冲两道门丢掉，正是线上「谁把会话拉起来」查不清的原因。
     */
    fun allowReconnect(trigger: ConnectTrigger): Boolean {
        val decision = AutoReconnectPolicy.decide(
            trigger = trigger,
            userDisconnected = carPrefs.userDisconnected,
            inCooldown = inUserDisconnectCooldown(),
        )
        val suppressed = decision.suppressed
        if (!decision.allowed && suppressed != null) {
            logWriter.sendUnconditional(
                "${AutoReconnectPolicy.LOG_MARKER} auto-reconnect suppressed " +
                    "(${suppressed.logText}, trigger=${trigger.label})"
            )
            return false
        }
        if (decision.clearUserDisconnected) {
            carPrefs.userDisconnected = false
            lastUserDisconnectAtMs = 0L
        }
        return true
    }

    fun startConnection(trigger: ConnectTrigger) {
        if (_state.value == CarConnectionService.State.STREAMING || _state.value == CarConnectionService.State.CONNECTED) return
        // issue #201：用户断开后，任何自动入口都必须在这里早退（标志不清除、状态不动）。
        if (!allowReconnect(trigger)) return

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
        surfaceWait.reset()
        adbTracker.resetAdbReadiness()

        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)
        // `[Connect]` 行跨会话保留（issue #201）：抑制发生在断线瞬间，只进得到离线缓冲，
        // 下个会话的 beginSession 若清掉它，手机侧就永远看不到「谁被挡下来了」。
        logWriter.beginSession(BydVehicleProbe.TAG, AutoReconnectPolicy.LOG_MARKER)
        // 放在 beginSession 之后：这行要随新会话一起送到手机，用来回答「谁发起的重连」。
        logWriter.sendUnconditional("${AutoReconnectPolicy.LOG_MARKER} start (trigger=${trigger.label})")
        _state.value = CarConnectionService.State.CONNECTING
        _statusMessage.value = context.getString(R.string.status_connecting)
        updateNotification(R.string.notification_searching)

        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            startWifiTrack()
            adbTracker.startUsbTrack(trackScope())
        }

        // issue #188 探针（临时诊断代码，见 armBydVehicleProbe 的注释）
        armBydVehicleProbe(parent)
    }

    /**
     * issue #188：连接建立后在车机侧读一次 BYDAuto 车辆数据，结果经 `CAR_LOG` 回传手机。
     *
     * 临时诊断代码 —— `BydVehicleProbe.ENABLED = false` 即整体失效，结论落定后连同
     * `probe/BydVehicleProbe.kt` 与测试一起删除（清理条件写在 issue #188 里）。
     *
     * 几个必须守住的点：
     * - **握手之后才开始**，并且走 [CarLogWriter.sendUnconditional] 而不是去动全局开关：
     *   手机端在握手后会下发 `LOG_TOGGLE`（release 手机默认关），临时 `setEnabled(true)`
     *   会与它抢同一个开关，跑完再还原还会把用户刚打开的日志关掉（#72 回归）；
     * - **IO 线程**：`scope` 是 `Dispatchers.Main`，探针内部有阻塞 sleep；
     * - **可中止**：探针没有挂起点，取消协程不会自动打断阻塞循环，必须把自己的 `isActive`
     *   传进去，否则断线后旧探针会跑完并往下一个会话继续回传；
     * - 会话在 `CONNECTED` 之前就结束时补一行 skipped，避免把「没跑」误读成「读不到」。
     */
    private fun armBydVehicleProbe(parent: Job) {
        if (!BydVehicleProbe.ENABLED) return
        scope.launch(parent + Dispatchers.IO) {
            try {
                _state.first {
                    it == CarConnectionService.State.CONNECTED || it == CarConnectionService.State.STREAMING
                }
            } catch (e: CancellationException) {
                probeLog("[BydProbe] skipped: session ended before CONNECTED", "W")
                throw e
            }
            val self = coroutineContext[Job]
            runCatching {
                BydVehicleProbe(
                    context = context,
                    sink = { probeLog(it) },
                    shouldAbort = { self?.isActive != true },
                ).run()
            }.onFailure {
                probeLog("[BydProbe] crashed: ${it.javaClass.simpleName}: ${it.message}", "E")
            }
        }
    }

    /** 探针专用上报：不受 `LOG_TOGGLE` 影响，否则手机上关着诊断日志时探针等于没跑。 */
    private fun probeLog(msg: String, level: String = "I") = logWriter.sendUnconditional(msg, level)

    fun beginConnect(host: String, port: Int, trigger: ConnectTrigger) {
        if (!allowReconnect(trigger)) return
        connectionScope?.cancel()
        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            adbTracker.startUsbTrack(trackScope())
        }
        armBydVehicleProbe(parent)
        logWriter.sendUnconditional("${AutoReconnectPolicy.LOG_MARKER} start (trigger=${trigger.label}, host=$host)")
        connectToPhone(host, port)
    }

    fun connectManual(host: String, port: Int = Ports.DEFAULT_PORT) {
        // issue #201 评审意见：先过闸门再推进状态，避免出现「状态已 CONNECTING、
        // 却没有连接 Job」的中间态（用户触发一定放行，这里只是不让时序倒置）。
        if (!allowReconnect(ConnectTrigger.USER_MANUAL_IP)) return
        _state.value = CarConnectionService.State.CONNECTING
        beginConnect(host, port, ConnectTrigger.USER_MANUAL_IP)
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
        // issue #201：网络恢复回调也走自动闸门，用户断开后不再重开探测循环。
        // TODO(#204)：available 不恢复是另一条独立缺陷，这里只加抑制，不改恢复语义。
        if (!allowReconnect(ConnectTrigger.AUTO_DISCOVERY)) return
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
        // issue #202 A：行车端只负责把「用户设置」原样下发；越界/非法值在这里就拒绝，
        // 不要交给 coerceDpiOverride 夹到边界 —— 那正是 issue #202 现场 `@480dpi` 的来源
        // （手滑输入 560 → UI 夹成 480 → 每次重连都按 480 建屏）。
        val storedDpi = carPrefs.startupDpi
        val dpiOverride = storedDpi.takeIf {
            it == SettingsFormat.DPI_AUTO ||
                it in VdDeployArgs.DPI_OVERRIDE_MIN..VdDeployArgs.DPI_OVERRIDE_MAX
        } ?: SettingsFormat.DPI_AUTO
        if (dpiOverride != storedDpi) {
            carLogSend(
                "W: startupDpi=$storedDpi 非法（只接受 0 或 " +
                    "${VdDeployArgs.DPI_OVERRIDE_MIN}..${VdDeployArgs.DPI_OVERRIDE_MAX}），本次按 Auto 下发"
            )
        }
        val handshake = buildHandshakeRequest(
            context = context,
            screenWidth = widthPx,
            screenHeight = heightPx,
            screenDpi = dpi,
            targetFps = targetFps,
            dpiOverride = dpiOverride,
            bitrate = carPrefs.startupBitrate,
            audioEnabled = carPrefs.audioEnabled,
            // issue #153 PB：与 startupDpi 同范式，握手构造时读取，切档在下一次
            // 连接 / 旋转重握手生效，绝不触发会话内 VD resize。
            vdAreaTier = carPrefs.vdAreaTier
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

        // issue #198：收到帧只证明手机在发流，不证明车机有可见输出。此前这里「收到非 CONFIG
        // 帧即 ready」，于是 SurfaceView 不可见（用户在主页）、解码器从未启动的会话也会报
        // `Video ready — streaming`，整段零画面。现在必须解码器在跑 + Surface 有效 +
        // 当前 Surface 上真的渲染过帧，才允许推进到 STREAMING。
        if (!_videoReady.value && !isConfig) {
            if (!tryMarkVideoReady()) {
                surfaceWait.onNotReady(SystemClock.elapsedRealtime())?.let { waitedSec ->
                    carLogSend(
                        "[Video] waiting for surface ${waitedSec}s " +
                            "(decoderRunning=${videoDecoder.isRunning} " +
                            "surfaceValid=${videoDecoder.outputSurfaceValid} " +
                            "renderedOnSurface=${videoDecoder.framesRenderedOnSurface})",
                        "W"
                    )
                }
            }
        }
        videoDecoder.onFrameReceived(isConfig, frame.payload)
    }

    /**
     * 就绪判定的唯一入口（issue #198 / #200）。
     *
     * 两个触发源共用它：
     *  - 收帧路径（[handleVideoFrame]）—— 码流正常时的常规采样；
     *  - 解码器「首帧真正上屏」回调（[onFirstFrameRenderedOnSurface]）—— 首帧渲染后码流
     *    停住（#200 的 6 秒停滞）时，收帧路径再也采样不到，必须由这条事件把 `videoReady`
     *    翻过来，否则 UI 会被「等待视频」遮罩一直盖住。
     *
     * 判定本身是一次性的（`_videoReady` 已 true 时直接返回），不会形成轮询。
     *
     * @return true 表示已就绪（含本次翻转）。
     */
    private fun tryMarkVideoReady(): Boolean {
        if (_videoReady.value) return true
        if (!RenderPipelinePolicy.canEnterStreaming(
                decoderRunning = videoDecoder.isRunning,
                outputSurfaceValid = videoDecoder.outputSurfaceValid,
                framesRenderedOnSurface = videoDecoder.framesRenderedOnSurface,
            )
        ) {
            return false
        }
        _videoReady.value = true
        surfaceWait.reset()
        carLogSend("Video pipeline live — decoded output is visible on the bound surface")
        checkAndAdvance()
        return true
    }

    /**
     * 解码器首帧真正上屏（issue #200）。
     *
     * 由 [VideoDecoder.onFirstFrameOnSurface] 在喂帧线程、持有 codecLock 时回调，
     * 这里只做一次轻量投递，把日志与状态机工作挪回 service scope。
     */
    fun onFirstFrameRenderedOnSurface() {
        if (_videoReady.value) return
        scope.launch { tryMarkVideoReady() }
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
                // issue #202 评审第 2 条：设置页要显示"实际生效值 / 视口上限"，
                // 两者都从这次握手响应里取（不新增协议）。
                _effectiveVdDpi.value = response.vdDpi
                _effectiveVdSafeMaxDpi.value = VideoConfig.maxSafeDpi(vdWidth, vdHeight)
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
        surfaceWait.reset()
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
        surfaceWait.reset()
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
            // 抑制判定收敛到 startConnection 的单一闸门（issue #201），这里不再重复读标志。
            if (_state.value == CarConnectionService.State.IDLE) {
                startConnection(ConnectTrigger.AUTO_RETRY)
            }
        }
    }

    fun disconnectFromPhone() {
        carLogSend("User disconnect — will not auto-reconnect")
        carPrefs.userDisconnected = true
        // issue #201：记下断开时刻，作为自动重连的冷却起点（用户点重连时清零）。
        lastUserDisconnectAtMs = SystemClock.elapsedRealtime()
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.DISCONNECT) } catch (_: Exception) {}
            // issue #183：显式走统一拆除路径，不再依赖 Connection.performTeardown 的
            // disconnectListener 副作用 —— 该连接此前若已 disconnect 过（connected 的
            // CAS 失败），listener 就不会再回调，`_state` 会停在 STREAMING、三处 Job
            // 仍 active，而 userDisconnected 又抑制了自动重连，表现为「必须杀后台」。
            // handleDisconnect() 自带 teardownGuard 防重入，并发路径同样会推到 IDLE。
            //
            // 拆除必须回到 service scope（Main）执行：handleDisconnect 内读写的
            // connectionScope/connectJob/discoveryJob/blackScreenRebuilds 都不是
            // volatile，既有口径是 Main（listener 也是 scope.launch{}），
            // 直接在 IO 上调会把它们变成跨线程竞争。
            scope.launch { handleDisconnect() }
        }
    }

    /**
     * 用户点「重新连接」（issue #183）：复用 [startConnection] 的完整启动流程 ——
     * 它会取消旧 Job、重置 teardownGuard、清除 userDisconnected 与冷却并置
     * CONNECTING。仅在 IDLE 等非会话态可用（STREAMING/CONNECTED 时 [startConnection]
     * 自身会直接返回）。
     *
     * issue #201：`USER_RECONNECT` 是**唯一**被允许清除 `userDisconnected` 的自动态之外
     * 的入口之一（另两个是手填 IP 与显式 CONNECT intent），因此这里绝不能把 trigger
     * 误写成 AUTO_*，否则按钮会看起来「按了没反应」。
     */
    fun reconnect() {
        carLogSend("User requested reconnect")
        startConnection(ConnectTrigger.USER_RECONNECT)
    }

    /**
     * 向手机索要一个 IDR（issue #75 / #163 的共享滑动窗口闸门）。
     *
     * 两个触发源共用这一个入口，绝不允许绕过闸门：
     *  - 解码器参考链断裂（[VideoDecoder.onNeedsSyncFrame]）；
     *  - Surface 重绑失败后的解码器重建（issue #198）：`stop()` 会清掉 `cachedKeyFrame`，
     *    不补一拍 IDR 就要等下一个自然 GOP，用户看到的是「重建后仍然黑几秒」。
     */
    fun requestSyncFrame(reason: String) {
        val now = SystemClock.elapsedRealtime()
        var granted = false
        while (true) {
            val current = syncRequestGate.get()
            val next = current.tryAcquire(now) ?: break
            if (syncRequestGate.compareAndSet(current, next)) {
                granted = true
                break
            }
        }
        if (granted) {
            carLogSend("Requesting IDR from phone ($reason)")
            sendCommandToVd(ControlMsg.REQUEST_SYNC_FRAME)
        } else {
            val lastGrant = syncRequestGate.get().grantsMs.lastOrNull()
            carLogSend(
                "IDR request throttled ($reason; window ${VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS}ms, " +
                    "max ${VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW} grants, " +
                    "last grant ${lastGrant?.let { now - it } ?: -1L}ms ago)"
            )
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
            // issue #198：分派判据抽到 RenderPipelinePolicy，Surface 已失效时不做无用重绑。
            when (RenderPipelinePolicy.onSurfaceCreated(
                decoderRunning = videoDecoder.isRunning,
                surfaceValid = surface.isValid,
            )) {
                RenderPipelinePolicy.SurfaceAction.IGNORE ->
                    carLogSend("[MirrorScreen] surface already invalid — waiting for the next callback", "W")

                RenderPipelinePolicy.SurfaceAction.START_DECODER -> {
                    videoDecoder.start(surface, vdWidth, vdHeight, targetFps)
                    carLogSend("[MirrorScreen] Decoder started on SurfaceView surface")
                }

                RenderPipelinePolicy.SurfaceAction.SWITCH_SURFACE -> {
                    // 重绑结果必须看返回值（issue #198）：旧实现无论成败都打成功日志，
                    // setOutputSurface 抛异常时渲染闸门永久关闭 → 整段黑屏却看不出问题。
                    if (videoDecoder.switchSurface(surface)) {
                        carLogSend("[MirrorScreen] Decoder surface switched to SurfaceView (no restart)")
                    } else {
                        // 回退重建：cached CONFIG 仍在，但 stop() 会清掉 cachedKeyFrame，
                        // 不补一拍 IDR 就要等下一个自然 GOP（issue #198）。
                        carLogSend("[MirrorScreen] surface rebind failed — rebuilding decoder on the new surface", "W")
                        videoDecoder.stop()
                        videoDecoder.start(surface, vdWidth, vdHeight, targetFps)
                        carLogSend("[MirrorScreen] Decoder rebuilt on SurfaceView surface")
                        // 走与参考链断裂同一个滑动窗口闸门（issue #75 / #163），不绕过限流。
                        requestSyncFrame("decoder rebuilt after surface rebind failure")
                    }
                }
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
