package com.dilinkauto.vdserver

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.dilinkauto.protocol.*
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * VD server entry point and lifecycle owner.
 *
 * The body is split into focused collaborators:
 *  - [GlPipeline] — EGL/GLES render + MediaCodec encode loop (pipeline thread).
 *  - [TouchInjector] — InputManager reflection + multi-touch state.
 *  - [DisplayPowerController] — physical panel power + IME policy/restore.
 *  - [VirtualDisplayCreator] — VirtualDisplay creation + environment overrides.
 *  - [PersistentShell] — the long-lived `sh` every command goes through.
 *  - [CarCommandRouter] — control-channel commands + display queries.
 *  - [LifecycleWriter] — the single-writer thread for lifecycle responses.
 *
 * This class owns: process lifecycle, encoder setup, socket bind/accept, the
 * lifecycle/touch reader threads, the watchdog, and cleanup ordering. It runs
 * as shell via `app_process`.
 *
 * Threading contract (audit S-06 / S-M8 / S-M9):
 *  - The pipeline thread is a **daemon** and is the only thread allowed to touch
 *    EGL/GL — the context was made current there. It waits for its go-ahead in
 *    bounded `parkNanos` slices so a failed `bindAndAccept` cannot park it
 *    forever and leave a zombie shell-UID process holding the VirtualDisplay and
 *    both binds.
 *  - `cleanup()` runs on main / Lifecycle / TouchReader / watchdog. It *requests*
 *    the pipeline thread's exit (`running=false` + `unpark` + bounded join) and
 *    never destroys EGL itself; the pipeline thread's own `finally` does that,
 *    guarded by `GlPipeline.glTornDown`.
 *  - `inputSurfaceReady` is counted down in a `finally` on the pipeline thread and
 *    awaited with a timeout on the main thread, and the watchdog is armed only
 *    after that await — so an EGL/GL init failure can no longer wedge the main
 *    thread while leaving `running` true (which the watchdog could not act on).
 */
class PipelineServer(
    private val displayWidth: Int, private val displayHeight: Int, private val dpi: Int,
    private val phoneHost: String, private val encodeWidth: Int, private val encodeHeight: Int,
    fps: Int, private val bitrate: Int = BITRATE,
    /**
     * S-01: receiver IP to pin 9638/9639 accepts to. null = accept any peer
     * (legacy behaviour for deployers that cannot determine their own outbound
     * address). The engine runs as shell UID with otherwise unauthenticated
     * ports, so anything that CAN determine it must pass it.
     */
    private val carHost: String? = null,
    /**
     * issue #21：车机可见应用的包名集合（手机端 allowlist）。只在 [CarCommandRouter]
     * 的建屏恢复里生效，约束 `ensureContent` 不得把白名单外的应用拉进 VD。
     * 空集合 = 部署端没下传（车机/桌面 ADB 路径），行为与引入前一致。
     */
    private val allowedPackages: Set<String> = emptySet()
) {
    /**
     * S-L2：fps 来自 argv，可能是遗留/被手改的 pref（含 0）。`1_000_000_000L / 0`
     * 会在构造器里抛 ArithmeticException，进程连 bind 都没到就死，所以先钳进
     * protocol-core 里唯一的那份 FPS 边界（[VideoConfig.MIN_FPS]/[VideoConfig.MAX_FPS]）。
     */
    private val fps = coerceFps(fps)
    // `this.fps` is deliberate: inside a property initializer the bare name `fps`
    // still resolves to the constructor parameter (uncoerced), which would reintroduce
    // the divide-by-zero this guard exists to prevent.
    private val frameIntervalNanos = 1_000_000_000L / this.fps
    @Volatile private var running = true
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupFinished = AtomicBoolean(false)
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    /** Pipeline 线程引用：cleanup() 靠它 unpark + 有界 join（S-06/S-M8）。 */
    @Volatile private var pipelineThread: Thread? = null

    private val shell = PersistentShell()

    // SurfaceTexture + VD surface (created on pipeline thread, used by VD)
    private var vdInputSurface: android.view.Surface? = null
    private var encoderSurface: android.view.Surface? = null

    private val touchInjector = TouchInjector(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        displayIdProvider = { displayId },
        shellProvider = { shell.input },
        logErr = { err(it) },
        // issue #64：成功路径的触控取证行带时钟（vd-server.log 本身没有时间戳）。
        logInfo = { PipeLog.logClock(it) }
    )
    /**
     * issue #64：周期采样 VD display 的窗口形态（`mLastNonfullscreenBounds`）。
     *
     * 启动时机在 `bindAndAccept` 成功之后：此时 `displayId` 已确定，也避开建屏期
     * 那串 `am`/`wm` 命令，避免额外的 dumpsys 与建屏步骤抢 shell。停止顺序在
     * cleanup 开头，确保它不会在 shell 关闭后再发 dumpsys。
     */
    private val windowMonitor = WindowStateMonitor(
        execOut = { shell.execOutput(it) },
        displayIdProvider = { displayId },
        log = { PipeLog.logClock(it) }
    )
    private val displayController = DisplayPowerController(
        shellProvider = { shell.input },
        execShellOutput = { shell.execOutput(it) },
        logErr = { err(it) }
    )
    private val vdCreator = VirtualDisplayCreator(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        dpi = dpi,
        // issue #153-PA：用 coerce 后的 fps，让 VD 合成节拍与目标帧率一致。
        refreshRate = this.fps.toFloat(),
        execShell = { shell.exec(it) },
        execShellOutput = { shell.execOutput(it) },
        log = { log(it) },
        err = { err(it) }
    )
    private val lifecycle = LifecycleWriter { running }
    private val watchdog = ProcessWatchdog(
        isRunning = { running },
        isCleanedUp = { cleanupFinished.get() },
        onStopRequested = {
            running = false
            pipelineThread?.let { LockSupport.unpark(it) }
        },
        onForceCleanup = {
            // issue #112：若主线程 cleanup() 在 resetLetterboxStyle/moveTasks 等步骤挂起，
            // 看门狗强制唤醒物理屏幕并恢复 IME，防止手机永久黑屏
            try { displayController.setPhysicalDisplayPower(true) } catch (e: Exception) { err("Watchdog force display power failed: ${e.message}") }
            try { shell.exec("input keyevent 224") } catch (_: Exception) {}
            try { displayController.restoreIme() } catch (_: Exception) {}
        },
        log = { log(it) },
        err = { err(it) }
    )
    private val carCommands = CarCommandRouter(
        exec = { shell.exec(it) },
        execOut = { shell.execOutput(it) },
        displayId = { displayId },
        onStackEmpty = { lifecycle.enqueue(VdLifecycle.MSG_STACK_EMPTY, ByteArray(0)) },
        setDisplayPower = { displayController.setPhysicalDisplayPower(it) },
        requestSyncFrame = { syncFrameRequested.set(true) },
        // issue #15：车机请求"显示自愈"时的最后一级 —— SF 层树脱同步修复，
        // 复用创建期那套旋转往返（见 VirtualDisplayCreator.ensureDtaAttached）。
        repairLayerTree = { vdCreator.ensureDtaAttached() },
        // issue #21：建屏恢复只允许挑车机 App 列表里的应用（空集合 = 不过滤）。
        allowedPackages = allowedPackages
    )
    private lateinit var glPipeline: GlPipeline

    // Synchronization between main thread and pipeline thread
    private val inputSurfaceReady = CountDownLatch(1)
    @Volatile private var carVideoChannel: SocketChannel? = null
    /**
     * S3-PA（issue #73）：9640 accept 成功且 [AudioPipeline.start] 成功后才被赋值。
     *
     * 为 null 表示本会话没有音频（无人连接 / 采集失败），这是 D2 下的正常状态。
     * cleanup() 在 [signalPipelineStop] **之前**关它（决策 4：先停音频、再停视频）。
     */
    @Volatile private var audioPipeline: AudioPipeline? = null

    /**
     * S3-PA（issue #73）：9640 的写端。与 [audioPipeline] 同生共死，但必须**分开**关：
     * `AudioPipeline.close()` 只停采集线程，不碰 socket；写线程与 channel 归 sink 管。
     */
    @Volatile private var audioSink: SocketAudioSink? = null
    /**
     * 车机端索要 IDR 的请求位（issue #9）。
     *
     * CarCommandRouter 在 Lifecycle/TouchReader 线程收到 REQUEST_SYNC_FRAME 只置位；
     * Pipeline 线程每帧 `getAndSet(false)` 消费后调 encoder.setParameters ——
     * GL/编码器状态只在 Pipeline 线程碰（见 GlPipeline 的线程模型说明）。
     */
    private val syncFrameRequested = AtomicBoolean(false)

    companion object {
        private const val BITRATE = VideoConfig.DEFAULT_BITRATE // alias — bitrate bounds live in VideoConfig
        /**
         * 关键帧间隔（秒）。issue #70：原来是 1，等价于每秒一次大 I 帧突发——
         * 实测（2.5h 日志 `keys=659 / 15731 frames @24fps`）正好 ≈1 IDR/s。
         * 突发撞上 WiFi 抖动时，256KB 发送缓冲 5s 排不空就把引擎打退重连。
         * 车机端缺帧时会主动索要 IDR（[CarCommandRouter] 的 REQUEST_SYNC_FRAME），
         * 所以拉长到 3s 只影响「自然刷新」节奏，不拖慢断链恢复。
         */
        private const val I_FRAME_INTERVAL = 3
        private const val WATCHDOG_GRACE_MS = ProcessWatchdog.WATCHDOG_GRACE_MS
        private const val SOCKET_BUF_BYTES = Connection.SOCKET_BUF_BYTES // single source: protocol-core

        /** S-M9：主线程等 EGL/GL 初始化的上限。超时即致命，绝不久久挂起。 */
        private const val INPUT_SURFACE_READY_TIMEOUT_SECONDS = 10L

        /**
         * S-06：Pipeline 线程等主线程建 VD/accept 的 park 粒度。
         *
         * 原来是无限期的 `LockSupport.park()`：没人 unpark 时永久挂住，而 running=false
         * 对已 park 的线程毫无作用。改成 parkNanos 循环后最坏一个轮询周期即可退出。
         */
        private const val PARK_POLL_NANOS = 100_000_000L

        /** S-M8：cleanup() 等 Pipeline 线程退出的上限；超时不阻塞，宁可泄漏 EGL。 */
        private const val PIPELINE_JOIN_TIMEOUT_MS = 2000L

        /**
         * S3-PA（issue #73）：9640 的 accept 超时。
         *
         * 3s 是「不让主线程无限等」与「给慢车机足够握手时间」的折中。它只在视频已开始
         * 渲染后由主线程等待（见 run()），失败即静默降级，不拖慢视频启动。
         */
        private const val AUDIO_ACCEPT_TIMEOUT_MS = 3000

        /** S-L2：把任意 fps（含 0/负数/荒谬值）钳进 [VideoConfig.MIN_FPS]..[VideoConfig.MAX_FPS]。 */
        fun coerceFps(fps: Int): Int = fps.coerceIn(VideoConfig.MIN_FPS, VideoConfig.MAX_FPS)

        @JvmStatic fun main(args: Array<String>) {
            AndroidPlatformHooks.install()
            // issue #8: 第一行先打可读版本号，车机侧不必反编译 dex 即可判断跑的是哪份构建。
            log("DiLink vd-server ${com.dilinkauto.vdserver.BuildConfig.VERSION_NAME}+g${com.dilinkauto.vdserver.BuildConfig.BUILD_REVISION}")
            val w = args.getOrNull(0)?.toInt() ?: 1408; val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120; val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w; val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: VideoConfig.TARGET_FPS
            val br = args.getOrNull(7)?.toInt() ?: BITRATE
            // S-01: peer pinning. The engine runs as shell UID and binds
            // 0.0.0.0:9638/9639 with no other authentication, so the deployer
            // passes the receiver IP it is serving; accepts from any other host
            // are refused. `-`/absent keeps the legacy accept-any behaviour.
            val carHost = args.getOrNull(8)?.takeIf { it.isNotBlank() && it != VdDeployArgs.CAR_HOST_ANY }
            // issue #21：第 10 个槽位是「允许上车的应用」逗号清单，`-`/缺省 = 不过滤。
            val allowed = VdDeployArgs.parseAllowedPackages(args.getOrNull(9))
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f, bitrate=${br/1_000_000}M, carHost=${carHost ?: "ANY"}")
            log("Allowlist: ${if (allowed.isEmpty()) "not handed down (no recents filtering)" else "${allowed.size} packages"}")
            PipelineServer(w, h, d, ph, ew, eh, f, br, carHost, allowed).run()
        }
        private fun log(msg: String) = PipeLog.log(msg)
        private fun err(msg: String) = PipeLog.err(msg)
    }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "ShutdownHook"))
        // 删除上一轮可能残留的停止哨兵（见 [VdDeploy.gracefulStopCommand] 的协议）：
        // 场景——外部写了请求、引擎未及消费即被强杀，文件残留；下一次 launch
        // 的新引擎必须忽略它，否则会"一启动就被上一轮的旧信号误杀"。
        runCatching { File(VdDeploy.STOP_REQUEST_PATH).delete() }
        try {
            touchInjector.initInputManager()
            shell.start()
            try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
            // setupEncoder 成功时 encoder/encoderSurface 已是一对可用的
            // create→configure→createInputSurface→start 实例（见其 KDoc）。
            if (encoder == null || encoderSurface == null) return

            glPipeline = GlPipeline(
                displayWidth, displayHeight, encodeWidth, encodeHeight, fps,
                frameIntervalNanos, bitrate
            ).also { it.encoderSurface = encoderSurface }

            // Start pipeline thread — it initializes EGL/GL and signals when VD input surface is ready.
            //
            // S-06: must be a daemon. It used to be a plain thread, so a pipeline thread
            // parked in LockSupport.park() forever (see runPipeline) kept the shell-UID
            // JVM — and with it the VirtualDisplay and both 0.0.0.0 binds — alive.
            val t = daemonThread("Pipeline", start = false) { runPipeline() }
            pipelineThread = t
            t.start()
            // S-M9: bounded wait. runPipeline countDown()s in a finally, so an EGL/GL init
            // failure surfaces here as `false` instead of parking the main thread forever
            // while running stayed true and the watchdog's outer loop never exited.
            val ready = try { inputSurfaceReady.await(INPUT_SURFACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { false }
            if (!ready) {
                running = false
                err("Fatal: EGL/GL init did not signal within ${INPUT_SURFACE_READY_TIMEOUT_SECONDS}s")
                return
            }
            // S-M9: arm the watchdog only now, i.e. after the main thread is guaranteed to
            // be unblocked. Armed before the await it could only observe running=true.
            startWatchdog()
            if (!createVirtualDisplay()) { running = false; err("Fatal: failed to create VD"); return }
            val conns = bindAndAccept() ?: run { running = false; return }
            startLifecycleReader(conns.phoneChannel)
            startTouchReader(conns.carInput)
            lifecycle.start()
            // issue #64：连接建立后开始周期采样窗口形态，给「竖屏小窗」留时间线上的证据。
            windowMonitor.start()
            // Signal pipeline to begin rendering with the car video channel
            carVideoChannel = conns.carVideo
            LockSupport.unpark(t)
            // S3-PA（issue #73）：9640 的 accept 放在视频已经开始渲染之后。
            //
            // 决策 3 的落点修正：把它塞进 bindAndAccept() 的串行 accept 之后，会因 D2 下
            // 「9640 无人连」是常态而给**每个会话**的视频启动白加 ≤3s 黑屏。放在 unpark
            // 之后，这段 ≤3s 只落在主线程自己身上（下一步本来就阻塞在 t.join()），视频
            // 零延迟。accept 失败/超时/!running → 不启动采集，会话照常继续。
            //
            // S-06：监听 socket 的所有权在这里从 bindAndAccept() 移交过来（见其 finally
            // 里的 handedOff），因此**关闭责任也一起移交** —— 用 finally 兜住，无论
            // accept 抛异常还是 startAudio 失败，9640 都不会跨会话泄漏。
            val audioServer = conns.audioServer
            if (audioServer == null) {
                err("Audio: ${Ports.AUDIO_PORT} not bound this session, audio disabled")
            } else {
                try {
                    val carAudio = acceptCarChannel(audioServer, AUDIO_ACCEPT_TIMEOUT_MS)
                    if (carAudio != null) {
                        startAudio(carAudio)
                    } else {
                        err("Audio: no receiver on ${Ports.AUDIO_PORT} within ${AUDIO_ACCEPT_TIMEOUT_MS}ms, audio disabled (capture not started)")
                    }
                } finally {
                    runCatching { audioServer.close() }
                }
            }
            try { t.join() } catch (_: InterruptedException) {}
        } finally {
            cleanup()
        }
    }

    /**
     * 选一个**真正能跑**的 H.264 编码器并完成 start（成功时 [encoder]/[encoderSurface]
     * 为一对可用实例）。
     *
     * 委托给 [VideoEncoderSelector] 完成候选枚举、排序与启动。
     */
    private fun setupEncoder() {
        val selected = VideoEncoderSelector.selectAndCreate(
            width = encodeWidth,
            height = encodeHeight,
            bitrate = bitrate,
            fps = fps,
            iFrameInterval = I_FRAME_INTERVAL
        )
        encoder = selected.codec
        encoderSurface = selected.surface
        log(
            "Encoder: ${selected.name} ${encodeWidth}x${encodeHeight} CBR@${bitrate / 1_000_000}Mbps Main ${fps}fps" +
                if (selected.skipped.isEmpty()) "" else "（跳过不可用: ${selected.skipped.joinToString(", ")}）"
        )
    }

    // ── VD Creation ──

    private fun createVirtualDisplay(): Boolean {
        val vdSurf = vdInputSurface ?: return false
        // Snapshot the system state we are about to change — BEFORE changing it.
        // This used to run after VirtualDisplayCreator.create(), which itself
        // wrote screen_off_timeout=SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL /
        // lift_wakeup=0 / proximity=0, so the "original" snapshot captured our own
        // writes and restoreIme() could never put the user's real values back.
        displayController.saveCurrentIme()
        virtualDisplay = vdCreator.create(vdSurf)
        if (virtualDisplay == null) return false
        val did = vdCreator.displayId
        if (did < 0) {
            PipeLog.err("VirtualDisplay displayId invalid: $did")
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            return false
        }
        displayId = did
        // Now that the originals are safely stored, apply the session overrides.
        vdCreator.configureEnvironment()
        try { displayController.setDisplayImePolicy(displayId) } catch (_: Exception) {}
        return true
    }

    // ── Connection ──

    data class ConnectionSet(
        val carVideo: SocketChannel,
        val carInput: SocketChannel,
        val phoneChannel: SocketChannel,
        /**
         * S3-PA（issue #73）：9640 的**监听** socket，null = 本次没绑上（端口被占用等）。
         *
         * accept 被刻意推迟到 `run()` 的 `LockSupport.unpark(t)` 之后（决策 3 落点修正），
         * 否则 D2 下「9640 无人连」会给每个会话的视频启动平白加 ≤3s。所以这里只带回
         * 绑好的监听 socket，accept 与关闭都由 `run()` 完成。
         *
         * 与 brief 里 `carAudio: SocketChannel?` 的写法不同：accept 发生在返回之后，
         * 用一个恒为 null 的字段没有意义。
         */
        val audioServer: ServerSocketChannel?
    )

    private fun bindAndAccept(): ConnectionSet? {
        val bounds = PortAcceptor.bindServers(
            videoPort = Ports.VIDEO_PORT,
            inputPort = Ports.INPUT_PORT,
            audioPort = Ports.AUDIO_PORT,
            onLog = { log(it) },
            onErr = { err(it) }
        ) ?: return null

        val videoServer = bounds.videoServer
        val inputServer = bounds.inputServer
        val audioServer = bounds.audioServer
        var handedOff = false
        try {
            val phoneChannel = connectToPhoneHost() ?: return null
            try { sendDisplayReady(phoneChannel); log("Display ready sent") } catch (e: Exception) { err("Display ready: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
            lifecycle.channel = phoneChannel
            shell.exec("input keyevent 224"); log("Waking up device to ensure VD activities resume")
            shell.exec("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"); log("Home launched")
            carCommands.moveTopApp(0, displayId)
            // VD 建好却没有任何窗口时编码器只能输出纯黑帧，车机侧判定黑屏后会反复重握手（issue #17）
            carCommands.ensureDisplayHasContent(displayId)
            // 校验 DTA 是否挂在 display 层树内（Flyme 偶发 reparent 事务丢失 → 0 层黑屏），必要时旋转往返修复
            vdCreator.ensureDtaAttached()
            // issue #59：DTA 修复之后再锁定横屏方向 —— 锁竖屏的应用否则会把 VD 转成居中的
            // 竖屏小窗（实车 mLastNonFullscreenBounds=Rect(920,51-1641,1351)）。
            vdCreator.lockLandscapeRotation()
            // Physical panel power-off happens *after* the car connects — see below.
            val carVideo = acceptCarChannel(videoServer, 30000) ?: run { err("Car video timeout"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
            val carInput = acceptCarChannel(inputServer, 30000) ?: run { err("Car input timeout"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
            log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")
            // Power the physical panel off only now — after the car holds both channels.
            displayController.setPhysicalDisplayPower(false)
            handedOff = true
            return ConnectionSet(carVideo, carInput, phoneChannel, audioServer)
        } finally {
            runCatching { videoServer.close() }
            runCatching { inputServer.close() }
            // 9640 监听 socket 在成功路径上要留给 run() 做 accept（由 run() 的 finally 关）；
            // 只有 return null 的路径才在这里关掉它（S-06：null 返回不许漏 bind）。
            if (!handedOff) audioServer?.let { runCatching { it.close() } }
        }
    }

    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, Ports.LIFECYCLE_PORT)
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                ch = SocketChannel.open(); ch.configureBlocking(false); ch.connect(addr)
                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) { if (!running || System.currentTimeMillis() > deadline) { ch.close(); throw ConnectException("timeout") }; Thread.sleep(50) }
                ch.configureBlocking(false); ch.socket().tcpNoDelay = true; return ch
            } catch (e: ConnectException) { ch?.close(); if (attempt < 59) Thread.sleep(200) }
            catch (e: Exception) { ch?.close(); err("Lifecycle connect: ${e.message}"); break }
        }
        return null
    }

    private fun sendDisplayReady(ch: SocketChannel) {
        val hasInjection = touchInjector.hasInjection
        val buf = ByteBuffer.allocate(6); buf.put(VdLifecycle.MSG_DISPLAY_READY); buf.putInt(displayId); buf.put(if (hasInjection) 1 else 0); buf.flip()
        ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false)
    }

    private fun acceptCarChannel(server: ServerSocketChannel, timeoutMs: Int): SocketChannel? =
        PortAcceptor.acceptCarChannel(
            server = server,
            timeoutMs = timeoutMs,
            socketBufBytes = SOCKET_BUF_BYTES,
            isAllowedPeer = { isAllowedPeer(it) },
            isRunning = { running },
            onRefusedPeer = { a ->
                err("Refused car channel from ${a.remoteAddress} (expected ${carHost ?: "ANY"})")
            }
        )

    /**
     * S3-PA（issue #73）：9640 accept 成功后才启动采集。
     *
     * ## 为什么必须等到 accept 之后（决策 3）
     *
     * `AudioRecord(REMOTE_SUBMIX)` 一旦创建，手机本机播放就会被路由走 —— 用户即使没
     * 要音频、甚至根本没有接收端，手机也会变哑。该资源只在 `AudioPipeline.start()`
     * 内部的 `createRecord()` 里创建，所以**不调 start 就等于不静音**。这里只有拿到
     * 真实 socket 才走到 start；accept 失败/超时没有任何副作用。
     *
     * `start()` 返回 false（`AudioRecord`/编码器不可用）时同样静默降级：关掉 socket、
     * 记一行日志，视频会话不受影响（R6）。
     */
    private fun startAudio(carAudio: SocketChannel) {
        val sink = SocketAudioSink(carAudio).also { it.start() }
        val pipeline = AudioPipeline(sink = sink)
        if (!pipeline.start()) {
            err("Audio: pipeline start failed (${pipeline.startFailure ?: "unknown"}), channel closed")
            runCatching { sink.close() }
            runCatching { carAudio.close() }
            return
        }
        audioSink = sink
        audioPipeline = pipeline
        log("Audio: channel accepted from ${carAudio.remoteAddress}")
        // 与 cleanup() 的竞态兜底：watchdog 可能在 start() 建 AudioRecord/编码器的那一百多毫秒
        // 里发现管线已死并跑完 cleanup()（它先置 running=false，再关 audioPipeline —— 那时还是
        // null）。若此处才赋值，采集线程会活过一个已经结束的会话，9640 也不会被关。
        // 双重关闭是安全的：AudioPipeline.close() 与 SocketAudioSink.close() 都幂等。
        if (cleanupStarted.get()) {
            err("Audio: session cleaned up during start, closing capture and channel")
            runCatching { pipeline.close() }
            runCatching { sink.close() }
            audioPipeline = null
            audioSink = null
        }
    }

    /** Host part of the accepted socket's remote address, or null when unknown. */
    private fun remoteHostOf(ch: SocketChannel): String? = try {
        (ch.remoteAddress as? InetSocketAddress)?.address?.hostAddress
    } catch (_: Exception) { null }

    private fun isAllowedPeer(ch: SocketChannel): Boolean {
        val expected = carHost ?: return true // no pinning requested
        val actual = remoteHostOf(ch) ?: return false
        return actual == expected
    }

    // ── Pipeline thread ──

    private fun runPipeline() {
        try {
            // Encoder + GL pipeline thread; mark urgent so background threads
            // (LifeWriter, watchdog) don't preempt the encode path on A53.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var eglReady = false
            try {
                glPipeline.initEglAndSurfaceTexture()
                vdInputSurface = glPipeline.vdSurface()
                eglReady = true
            } finally {
                // S-M9: countDown moved into the finally. It only used to run on the success
                // path, so an EGL/GL init exception left the main thread in a forever await()
                // with running==true — the one combination the watchdog cannot act on.
                inputSurfaceReady.countDown()
            }
            if (eglReady) {
                // Wait for main thread to create VD, accept connections, and set carVideoChannel.
                //
                // S-06: this was an unbounded LockSupport.park() with nobody to unpark it on
                // the failure paths (run() only unparks after a successful bindAndAccept).
                // running=false does not wake a parked thread, so the thread — and the whole
                // shell-UID process, since it was not a daemon — stayed alive forever holding
                // the VirtualDisplay and both binds. Now it parks in bounded slices and
                // re-checks running; cleanup() additionally unparks explicitly.
                while (carVideoChannel == null && running) LockSupport.parkNanos(PARK_POLL_NANOS)
                val ch = carVideoChannel
                val enc = encoder
                if (running && ch != null && enc != null) {
                    glPipeline.pipelineLoop(enc, ch, { running }, { syncFrameRequested.getAndSet(false) })
                }
            }
        } catch (e: Exception) {
            err("Pipeline: ${e.message}")
            // 对端断开（Broken pipe 等）是会话结束的正常形态：一行消息足够，不打完整堆栈
            // （S19 真机实证：20 轮循环 9 次 Broken pipe 堆栈把 vd-server.log 的 Exception 断言打爆，
            // 堆栈对断连无任何诊断价值）。
            if (!isPeerDisconnect(e)) e.printStackTrace()
        }
        finally {
            // S-M8: EGL/GL teardown belongs to THIS thread — the EGL context was made
            // current here, and the encoder may still be inside dequeueOutputBuffer.
            // cleanup() on main/Lifecycle/TouchReader/watchdog can only request the exit
            // (running=false + unpark + bounded join); it never destroys EGL itself.
            releaseGlResources()
        }
        log("Pipeline exited")
    }

    /**
     * S-M8：销毁 GL/EGL 资源（幂等），且**只能**在 Pipeline 线程执行。
     *
     * 之前 `cleanup()` 从任意调用方线程（main / Lifecycle / TouchReader / Watchdog）
     * 直接 `glPipeline.cleanup()`：对一条 EGL context 由别的线程 makeCurrent 的线程
     * 做 eglDestroySurface/eglDestroyContext 属于跨线程 GL teardown，会和编码器的
     * native 调用打架。非属主线程调用这里只做"请求已发出"的记录，真正的销毁由
     * [runPipeline] 的 finally 完成；Pipeline 线程已退出时它当然也已经做完了。
     */
    private fun releaseGlResources() {
        if (!::glPipeline.isInitialized) return
        val owner = pipelineThread
        if (Thread.currentThread() !== owner) {
            if (owner != null && owner.isAlive) {
                err("GL teardown deferred to the Pipeline thread (still alive) — EGL may leak if it never exits")
            }
            return
        }
        try { glPipeline.cleanup() } catch (e: Exception) { err("GL cleanup: ${e.message}") }
    }

    /**
     * S-06/S-M8：请求 Pipeline 线程退出并等待它（有界）。
     *
     * `running=false` + 显式 unpark 是唤醒 parkNanos 循环的那一对：只有 run() 在成功
     * 路径 unpark 过一次，bindAndAccept 失败时没人 unpark。join 设上限是为了不在
     * cleanup 里永久阻塞 —— 线程卡在 native MediaCodec 调用里时，宁可泄漏 EGL 也不能
     * 把 panel 恢复一起拖死（那正是 watchdog 存在的原因）。
     */
    private fun signalPipelineStop() {
        running = false
        val t = pipelineThread ?: return
        LockSupport.unpark(t)
        try { t.join(PIPELINE_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (t.isAlive) err("Pipeline thread did not exit within ${PIPELINE_JOIN_TIMEOUT_MS}ms — GL/encoder teardown skipped")
    }

    // ── Lifecycle + Touch ──

    private fun startLifecycleReader(ch: SocketChannel) {
        daemonThread("Lifecycle", start = true) {
            try { readLifecycleCommands(ch) } catch (e: Exception) { if (running) err("Lifecycle: ${e.message}") }
        }
    }
    private fun readLifecycleCommands(ch: SocketChannel) {
        val r = NioReader(ch, 4096, frameIntervalNanos / 1_000_000)
        try {
            while (running) {
                if ((r.readByteBlocking().toInt() and 0xFF) == VdLifecycle.CMD_STOP) {
                    log("CMD_STOP")
                    break
                }
            }
        } catch (e: IOException) {
            if (running) err("Lifecycle: ${e.message}")
        } finally {
            running = false
            r.close()
            try { ch.close() } catch (_: Exception) {}
            cleanup()
        }
    }

    private fun startTouchReader(carInput: SocketChannel) {
        daemonThread("TouchReader", start = true) {
            try { readTouchAndCommands(carInput) } catch (e: Exception) { err("Touch: ${e.message}") }
        }
    }

    /**
     * Watchdog: 委托给 [ProcessWatchdog] 进行停止哨兵文件轮询与强制退出监控。
     */
    private fun startWatchdog() {
        watchdog.start()
    }

    private fun readTouchAndCommands(ch: SocketChannel) {
        val r = NioReader(ch, 65536, frameIntervalNanos/1_000_000)
        try {
            while (running) {
                val f = try { FrameCodec.readFrameBlocking(r) } catch (e: Exception) { null }
                if (f == null) break
                when (f.channel) {
                    Channel.INPUT -> touchInjector.handleTouchFrame(f)
                    Channel.CONTROL -> carCommands.dispatch(f)
                }
            }
        } finally {
            // Peer of readLifecycleCommands: when the car-input socket dies alone,
            // tear the whole pipeline down so the physical panel recovers
            // (setPhysicalDisplayPower(true) runs in cleanup()) instead of
            // leaving the encoder spinning with no consumer.
            running = false
            r.close()
            try { ch.close() } catch (_: Exception) {}
            cleanup()
        }
    }

    /**
     * Idempotent teardown. Order is deliberate:
     *
     *  1. Global window/rotation state is reset FIRST, while the shell is
     *  guaranteed alive and before anything that can block. These two are
     *  device-wide (not per-display), so a leaked value corrupts every later
     *  session, and they are cheap enough that doing them up front means even a
     *  process killed midway has already returned the device to normal.
     *  2. The foreground app is moved back to the physical display.
     *  3. The physical panel is re-powered and the IME restored — while the
     *  persistent shell is still usable (`setPhysicalDisplayPower` needs it).
     *  4. Threads, GL, encoder, and finally the VirtualDisplay are released.
     *
     * The previous order had `virtualDisplay.release()` dead last with nothing
     * after it to fail, so a mid-way exception or a SIGKILL from the phone side
     * left the panel off and the VD alive — one leaked display per reconnect.
     */
    /**
     * issue #48：`reset-letterbox-style` 原先 fire-and-forget，失败/未生效都会被静默吞掉。
     * 这里执行后读 `get-letterbox-style` 的 `Aspect ratio:`，不是 0.0 再重试一次；
     * 仍失败只落日志，不阻塞 cleanup。
     */
    private fun resetLetterboxStyle() {
        for (attempt in 1..2) {
            val out = shell.execOutput("cmd window reset-letterbox-style")
            val state = shell.execOutput("cmd window get-letterbox-style")
            if (state != null && Regex("Aspect ratio:\\s*0\\.0").containsMatchIn(state)) {
                log("[teardown-display] reset-letterbox-style ok (attempt=$attempt, out=${out?.trim() ?: "<none>"})")
                return
            }
            err(
                "[teardown-display] reset-letterbox-style not effective " +
                    "(attempt=$attempt, out=${out?.trim() ?: "<none>"}, " +
                    "aspect=${state?.lineSequence()?.firstOrNull { it.contains("Aspect ratio") }?.trim() ?: "<unknown>"})"
            )
        }
    }

    private fun cleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        running = false
        // issue #64：先停窗口采样线程，后面的 shell 恢复命令与 close 才不会被 dumpsys 插队。
        windowMonitor.stop()
        // 1. Global state first — these outlive this process if we die.
        // issue #48：letterbox 残留会被用户描述成「显示尺寸/比例不对」；带读回校验 + 一次重试。
        try { resetLetterboxStyle() } catch (e: Exception) {
            err("[teardown-display] reset-letterbox-style failed: ${e.message}")
        }
        if (displayId >= 0) {
            try { shell.exec("wm user-rotation -d $displayId free") } catch (_: Exception) {}
        }
        // 2. 先把前台 app 搬回，再把 VD 上**所有**残留 task 搬回并 resize（issue #48）；
        //    只搬顶层会让 quickSwitch 历史任务带着 VD 的 density/bounds 留在物理屏。
        carCommands.moveTopApp(displayId, 0)
        carCommands.moveAllTasksToDisplay(displayId, 0)
        // 3. Restore screen BEFORE killing shell (order matters: shell.exec needs the shell alive)
        displayController.setPhysicalDisplayPower(true)
        try { shell.exec("input keyevent 224") } catch (_: Exception) {}
        displayController.restoreIme()
        // 3b. issue #48 取证：面板/IME 已恢复后再读快照，避免 dumpsys 拖慢恢复顺序。
        // 只读：显示 display#0 的 init/cur 与仍留在 VD 上的 task id，不改任何状态。
        carCommands.logTeardownDisplaySnapshot(displayId)
        // 4. Stop the LifeWriter thread before killing the shell — it may be mid-write.
        lifecycle.stop()
        // 4b. 屏障（2026-10-10，MI 9 实测）：上面的恢复命令是 fire-and-forget 写进
        // 常驻 sh 的 stdin，而 close() 的 destroy() 会立刻 SIGTERM 掉它 —— 最后
        // 几条命令还没被 sh 读走就随进程死了。实测丢的正是
        // `settings put system screen_off_timeout 60000`：日志完整显示"恢复成功 +
        // Cleanup complete"，但会话结束后值仍停在 2147483647 哨兵。echo 一个唯一
        // 标记并等排水线程回显（stdin/stdout 双 FIFO）⇒ 此前命令都已执行完。
        shell.awaitIdle()
        // Now kill the shell
        shell.close()
        // S3-PA（issue #73）决策 4：先停音频、再停视频。
        //
        // 音频必须在 signalPipelineStop() 之前关：AudioPipeline.close() 自己有 1.5s 的有界
        // join（AudioPipeline.CLOSE_JOIN_TIMEOUT_MS），与视频那条 2s 预算串联而非叠加在同一
        // 段里，cleanup 的总耗时仍可控。放在之前还保证采集线程先停，不会在 VD/编码器已经
        // 拆掉的窗口里继续读 REMOTE_SUBMIX。
        //
        // 顺序是「先采集、后写端」：采集停掉之后不会再有 onFrame 进来，此时关 sink 才能让
        // 写线程排空退出并真正关掉 9640 的 socket（sink 是 channel 的唯一属主）。
        audioPipeline?.let { p ->
            // AudioPipeline.close() 自己会打一行 `Audio: capture stopped (frames=..., silence=...)`。
            runCatching { p.close() }.onFailure { err("Audio: close failed: ${it.describe()}") }
        }
        audioPipeline = null
        audioSink?.let { s ->
            runCatching { s.close() }
                .onFailure { err("Audio: sink close failed: ${it.describe()}") }
            log("Audio: sink closed (sent=${s.sentFrames}, configs=${s.sentConfigs}, dropped=${s.droppedFrames}, failed=${s.failed})")
        }
        audioSink = null
        // S-M8/S-06: ask the pipeline thread to exit (running=false + unpark) and wait for it
        // (bounded) BEFORE touching the encoder — the render loop may still be inside a
        // native dequeueOutputBuffer/eglSwapBuffers call, which is what used to race
        // cleanup() here.
        signalPipelineStop()
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        // S-M8: the encoder's input Surface used to be leaked — releasing the codec does not
        // release the Surface we handed it, so every session burned one for nothing.
        encoderSurface?.let { try { it.release() } catch (_: Exception) {} }
        // S-M8: EGL/GL is torn down by the pipeline thread itself (releaseGlResources);
        // signalPipelineStop above is how this thread asks for it.
        releaseGlResources()
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }
        log("Cleanup complete")
        cleanupFinished.set(true)
    }
}

/**
 * 对端断开类 IO 异常（Broken pipe / Connection reset / Socket closed）。
 *
 * 会话被对端终结（Eject / 拔线 / 引擎被重建）时，视频写路径会抛这些异常——它们是
 * **正常终止形态**，[PipelineServer] 的兜底 catch 对它们只打一行消息、不打完整堆栈
 * （S19 真机实证：20 轮循环 9 次 Broken pipe 堆栈把 vd-server.log 的 Exception 断言
 * 打爆；堆栈对这类断连没有诊断价值）。非 IO 异常 / 消息不匹配一律返回 false（照旧打堆栈）。
 */
internal fun isPeerDisconnect(e: Throwable): Boolean {
    if (e !is IOException) return false
    val m = e.message ?: return false
    return m.contains("Broken pipe", ignoreCase = true) ||
        m.contains("Connection reset", ignoreCase = true) ||
        m.contains("Socket closed", ignoreCase = true)
}
