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
    private val allowedPackages: Set<String> = emptySet(),
    /**
     * issue #180 第三版：本次会话的 token（部署方生成、经 argv 第 11 槽下传）。
     *
     * 非空时只监听 `stop-request-<token>`；空 = 旧客户端/老脚本，回退固定名
     * `stop-request` + mtime。见 [VdDeploy] 类头"会话归属"。
     */
    private val sessionToken: String = ""
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

    /**
     * issue #180：本会话（进程）启动时刻，watchdog 据此判断停止哨兵的会话归属。
     *
     * 必须在 [watchdog] 之前初始化（属性按声明顺序求值）。哨兵是全局固定文件名，
     * 早于这个时刻的 mtime 一律是上一轮会话的残留。
     */
    private val sessionStartedAtMs = System.currentTimeMillis()

    /**
     * issue #180：本进程 pid —— 旧格式哨兵的**内容寻址**标识。
     *
     * 启动时写进 [VdDeploy.PID_PATH]，旧格式停止方把它抄进哨兵；没有 token 的引擎
     * 只受理内容等于自己 pid 的请求，mtime 只作为空内容（空文件）的回退判据。
     */
    private val sessionPid: String = android.os.Process.myPid().toString()

    /**
     * issue #180 第三版：本会话监听的哨兵路径。
     *
     * 有 token → `stop-request-<token>`（角色由文件名决定）；无 token → 旧格式固定名。
     */
    private val stopRequestPath: String = VdDeploy.stopRequestPath(sessionToken)
    @Volatile private var running = true
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupFinished = AtomicBoolean(false)
    /**
     * issue #199：收尾真正落到"最后一步之后"的信号。
     *
     * `cleanup()` 可能跑在 daemon 线程（Lifecycle / TouchReader）上，主线程 `run()` 返回
     * 后 JVM 会把 daemon 就地杀掉 —— 现场因此从未打出过 `18/18`（见 [awaitCleanupFinished]）。
     */
    private val cleanupDone = CountDownLatch(1)
    /** issue #199：收尾进度（步号 + 标签），watchdog 超时 / 补偿标记里一并打出。 */
    @Volatile private var teardownProgress: String = ""
    /** issue #199：还没搬回物理屏的 task id（`[a, b]`），供超时现场与补偿标记使用。 */
    @Volatile private var teardownPendingTasks: String = ""
    /** PR #208 评审：最近一次被放弃（超时/失败/跳过）的收尾步骤，供后续步骤做保护。 */
    @Volatile private var abandonedStep: TeardownStep? = null
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
            // issue #199 C：被强杀前先落"收尾未完成"标记，下一次启动据此做补偿清理
            // （避免任务滞留已销毁的 display、panel/IME 状态未知）。
            markCleanupIncomplete("watchdog")
            // issue #112：若主线程 cleanup() 在 resetLetterboxStyle/moveTasks 等步骤挂起，
            // 看门狗强制唤醒物理屏幕并恢复 IME，防止手机永久黑屏
            try { displayController.restorePanel() } catch (e: Exception) { err("Watchdog force display power failed: ${e.message}") }
            try { shell.exec("input keyevent 224") } catch (_: Exception) {}
            try { displayController.restoreIme() } catch (_: Exception) {}
        },
        // issue #199 B：超时消息必须带"卡在哪一步 / 还剩哪些 task"，否则现场仍只有一句
        // "cleanup did not complete"，下次还得靠猜。
        progress = {
            "at ${teardownProgress.ifBlank { "<未开始>" }}, " +
                "remainingTasks=${teardownPendingTasks.ifBlank { "[]" }}"
        },
        log = { log(it) },
        err = { err(it) },
        sessionToken = sessionToken,
        stopRequestPath = stopRequestPath,
        sessionStartedAtMs = sessionStartedAtMs,
        sessionPid = sessionPid
    )
    private val carCommands = CarCommandRouter(
        exec = { shell.exec(it) },
        execOut = { shell.execOutput(it) },
        displayId = { displayId },
        onStackEmpty = { lifecycle.enqueue(VdLifecycle.MSG_STACK_EMPTY, ByteArray(0)) },
        // issue #66：熄屏受能力闸门约束，点亮（含车机侧手动亮屏）无条件尝试。
        setDisplayPower = { on -> if (on) displayController.restorePanel() else displayController.powerOffPanel() },
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

        /**
         * issue #165：cleanup 阶段诊断日志开关（默认开，每会话约 18 行）。
         *
         * FORTIFY 只有一行地址、没有调用栈；这些 step 行把收尾切成可对账的阶段。
         * 启动引擎时带 `DILINK_TEARDOWN_TRACE=0` 可关闭（默认开是为了下一次现场就能二分）。
         */
        private val TEARDOWN_TRACE_ENABLED: Boolean = System.getenv("DILINK_TEARDOWN_TRACE") != "0"

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
            // issue #180 第三版：第 11 个槽位是本次会话的 token，`-`/缺省 = 旧格式固定名。
            val sessionToken = VdDeployArgs.parseSessionToken(args.getOrNull(10))
            // issue #202 A：第 12 槽位是部署端解析出的 DPI 来源（`auto`/`user-240`/`fallback-480-234`）。
            // 引擎只把最终生效值与来源打进启动行，现场不必再靠猜"这个 dpi 是谁定的"。
            val dpiSource = VdDpiResolver.parseToken(args.getOrNull(11))
            log("Starting: VD=${w}x${h} @${d}dpi (dpiSource=$dpiSource), encode=${ew}x${eh}, phoneHost=$ph, fps=$f, bitrate=${br/1_000_000}M, carHost=${carHost ?: "ANY"}")
            log("Allowlist: ${if (allowed.isEmpty()) "not handed down (no recents filtering)" else "${allowed.size} packages"}")
            log(
                "Stop sentinel: " + if (sessionToken.isEmpty()) {
                    "${VdDeploy.STOP_REQUEST_PATH} (legacy, mtime + pid addressing)"
                } else {
                    "${VdDeploy.stopRequestPath(sessionToken)} (this session only)"
                }
            )
            PipelineServer(w, h, d, ph, ew, eh, f, br, carHost, allowed, sessionToken).run()
        }
        private fun log(msg: String) = PipeLog.log(msg)
        private fun err(msg: String) = PipeLog.err(msg)
    }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "ShutdownHook"))
        // issue #180：第一步就发布本进程标识（pid + token，原子写），让停止方能把请求
        // 投递到具体会话。放在 EGL/编码器初始化之前——初始化窗口内的真停止请求也能被识别。
        publishSessionFiles()
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
            //
            // issue #180：启动编排是**清场 → 武装 → 建屏**（见 [runStartupSequence]）。
            //
            // 清场必须紧跟武装之前，而不是 run() 开头：放开头时它与 `startWatchdog()`
            // 之间隔着一整段 setupEncoder + EGL/GL 初始化（上限 10s），上一轮会话的收尾
            // 若与本次 launch 并发，哨兵就会落进「已清过场、watchdog 还没开始读」的窗口
            // （现场日志：`EGL/GL ready, VD input surface created` 的下一行就是
            // `Stop request detected ... graceful shutdown`，整段会话不足 1 秒）。
            //
            // watchdog 开始轮询之前，本进程还没有任何可被停止的会话，因此此刻盘上的
            // **旧格式**哨兵一律清掉 —— 内容写给本进程 pid 的除外（那是初始化窗口里
            // 用户按下的真停止，见 [shouldDropStartupStopRequest]）。会话专属哨兵
            // （`stop-request-<token>`）不删：只有持有本会话 token 的部署方会写它。
            val vdReady = runStartupSequence(
                clearStartupStopRequest = {
                    clearStartupStopRequest()
                    // issue #199 C：上一轮被 watchdog 强杀时留下的补偿清理。放在清场之后、
                    // 建屏之前：此时 shell 已可用，且不会与新建 VD 的 dumpsys 抢同一段预算。
                    compensateIncompleteCleanup()
                },
                armWatchdog = { startWatchdog() },
                createVirtualDisplay = { createVirtualDisplay() }
            )
            if (!vdReady) { running = false; err("Fatal: failed to create VD"); return }
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
            // issue #199：收尾可能发生在 Lifecycle / TouchReader 这类 **daemon** 线程上
            // （见 cleanup() 类头）。主线程在这里返回后，JVM 会直接拆掉 daemon 线程 ——
            // 现场因此从来没有打出过 `18/18` 与 `Cleanup complete`，`removeSessionFiles()`
            // 也从未执行。这里先有界等待收尾真正落地（预算是编排器总预算 + 1s 余量）。
            awaitCleanupFinished(TEARDOWN_TOTAL_BUDGET_MS + 1_000L)
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
        // issue #202 B：先问一句"能不能复用"——旧 VD 的 display 仍存活、surface 有效且
        // 尺寸/DPI 与本次目标一致时，不重建、也不碰它上面的任务（ensureContent 会看到
        // 可见窗口直接返回）。这样避免"建屏 → 搬回 display 0 → 再搬回来"的抖动。
        //
        // 说明：跨**进程**复用不可能（VD 的生命周期绑定创建它的进程，surface 也随进程
        // 失效），所以这条路径的服务对象是本进程内的重复建屏/交接；跨会话复用需要引擎
        // 常驻，属于另一个 issue 的范围（见 #202 的 plan 追评）。
        val existing = virtualDisplay?.let { vd ->
            VdSnapshot(
                displayId = displayId,
                width = displayWidth,
                height = displayHeight,
                dpi = dpi,
                // 不能只看对象非空：release() 之后引用还在，但 display 已销毁。
                alive = try { vd.display != null && vd.display.isValid } catch (_: Exception) { false }
            )
        }
        val decision = VdReusePolicy.decide(
            current = existing,
            target = VdSnapshot(displayId, displayWidth, displayHeight, dpi, alive = true),
            surfaceValid = try { vdSurf.isValid } catch (_: Exception) { false }
        )
        if (decision.reuse) {
            log("VD reuse: ${decision.reason} — 不重建、不搬任务")
            return true
        }
        log("VD create: ${decision.reason}")
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
            displayController.powerOffPanel()
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
        // issue #165：采集闸门接会话状态。cleanup() 第一行 `running=false` 后，采集线程
        // 立即停止投喂，不再横跨整个 shell 恢复阶段继续向编码器 queueInputBuffer。
        val pipeline = AudioPipeline(sink = sink, sessionRunning = { running })
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
     * issue #180：把本进程的标识发布到共享存储（原子写，评审第 2 条）。
     *
     *  - [VdDeploy.PID_PATH]：本进程 pid，旧格式停止方的内容寻址标识；
     *  - [VdDeploy.SESSION_TOKEN_PATH]：本会话 token，供"停掉当前引擎"（启动前置
     *    清场）读取——只有 token 非空时才写。
     *
     * 写失败不致命：旧格式停止方退化成空文件（mtime 判定），新客户端则靠文件名
     * 归属，两者仍能停。
     */
    private fun publishSessionFiles() {
        val pidOk = writeTextAtomically(VdDeploy.PID_PATH, sessionPid)
        if (!pidOk) {
            err("Startup: failed to publish ${VdDeploy.PID_PATH} — legacy stop requests fall back to mtime mode")
        }
        if (VdDeploy.isValidSessionToken(sessionToken)) {
            val tokenOk = writeTextAtomically(VdDeploy.SESSION_TOKEN_PATH, sessionToken)
            if (!tokenOk) {
                err("Startup: failed to publish ${VdDeploy.SESSION_TOKEN_PATH} — pre-launch cleanup cannot target this session")
            }
        }
    }

    /**
     * issue #180：watchdog 武装前的清场——删掉不属于本会话的停止哨兵。
     *
     * 调用点由 [runStartupSequence] 固定在 [startWatchdog] 之前。
     *
     *  - 会话专属哨兵（[stopRequestPath] 是 `stop-request-<token>`）：**保留**，
     *    只有持有本会话 token 的部署方会写它（初始化窗口里的真停止）。
     *  - 旧格式固定名：token 模式下本会话根本不读它，盘上残留直接清掉；旧格式模式
     *    下按 [shouldDropStartupStopRequest] 判定（内容写给本进程 pid 的保留）。
     *  - token 模式顺手清掉上一轮会话的 `stop-request-<旧 token>`（它们的引擎已退出，
     *    永远不会有人消费）。
     */
    private fun clearStartupStopRequest() {
        if (VdDeploy.isValidSessionToken(sessionToken)) {
            // 旧格式固定名在 token 模式下仍是**车机 ADB 部署路径**的兜底通道（手机端
            // 没有 token），内容写成本进程 pid 的必须保留；其余一律清掉。
            val legacy = File(VdDeploy.STOP_REQUEST_PATH)
            if (legacy.exists()) {
                val content = runCatching { legacy.readText() }.getOrNull()
                if (isLegacyStopRequestForTokenSession(content, sessionPid)) {
                    log("Startup: kept legacy stop-request addressed to this pid ($sessionPid)")
                } else {
                    dropFiles("stale legacy stop-request", listOf(legacy))
                }
            }
            // 上一轮会话的 `stop-request-<旧 token>`：它们的引擎已退出，永远不会有人消费。
            dropFiles("other-session sentinels", otherSessionSentinelFiles())
            return
        }
        val f = File(stopRequestPath)
        if (!f.exists()) return
        val content = runCatching { f.readText() }.getOrNull()
        if (!shouldDropStartupStopRequest(content, sessionPid, sessionToken)) {
            log("Startup: kept stop-request addressed to this pid ($sessionPid)")
            return
        }
        val removed = runCatching { f.delete() }.getOrDefault(false)
        log("Startup: dropped pre-arm stop-request (deleted=$removed)")
    }

    /** 本会话以外的 `stop-request-<token>` 残留（token 模式下由 [clearStartupStopRequest] 清掉）。 */
    private fun otherSessionSentinelFiles(): List<File> {
        val own = File(stopRequestPath).name
        val dir = File(VdDeploy.DIR_PATH)
        return dir.listFiles { f ->
            f.isFile && isOtherSessionSentinelName(f.name, own)
        }?.toList().orEmpty()
    }

    private fun dropFiles(reason: String, files: List<File>) {
        if (files.isEmpty()) return
        val removed = files.count { runCatching { it.delete() }.getOrDefault(false) }
        log("Startup: dropped $removed $reason")
    }

    /**
     * issue #180：收尾时撤掉本会话发布在共享存储上的标识（评审第 2 条：原来只写不删）。
     *
     * 只删**内容仍属于本进程**的文件：下一代引擎可能已经启动并发布了自己的 pid/token，
     * 无条件删除会把它的标识抹掉（见 [deleteIfContentMatches]）。
     */
    private fun removeSessionFiles() {
        deleteIfContentMatches(VdDeploy.PID_PATH, sessionPid)
        if (VdDeploy.isValidSessionToken(sessionToken)) {
            deleteIfContentMatches(VdDeploy.SESSION_TOKEN_PATH, sessionToken)
        }
        runCatching { File(stopRequestPath).delete() }
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
            // (displayController.restorePanel() runs in cleanup()) instead of
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
     *  persistent shell is still usable (`restorePanel` needs it).
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

    /**
     * issue #199：收尾的步骤编排 —— 18 步、每步独立预算、总预算兜底。
     *
     * 顺序与语义逐条沿用 issue #165 的既有契约（`TeardownStep` 仍是 18 项，
     * `[teardown-step] NN/18 label` 仍能在历史日志里对账），只是把「执行」交给
     * [TeardownRunner]：单步超时只放弃那一步并继续，不再让一步慢把后面全部掐掉。
     *
     * 预算取值依据 2026-11-16 现场实测（`.tmp/run-1116/vd-server.log`）：letterbox 210ms、
     * shell-drained ≤193ms、audio-close ≤5ms、pipeline-join ≤3ms、apps-moved 861~3079ms。
     */
    private fun buildTeardownPlan(): List<TeardownStepPlan> = listOf(
        TeardownStepPlan(TeardownStep.BEGIN, TEARDOWN_BUDGET_BEGIN_MS) {},
        // issue #64：先停窗口采样线程，后面的 shell 恢复命令与 close 才不会被 dumpsys 插队。
        TeardownStepPlan(TeardownStep.WINDOW_MONITOR_STOPPED, TEARDOWN_BUDGET_WINDOW_MONITOR_MS) {
            windowMonitor.stop()
        },
        // 1. Global state first — these outlive this process if we die.
        // issue #48：letterbox 残留会被用户描述成「显示尺寸/比例不对」；带读回校验 + 一次重试。
        TeardownStepPlan(TeardownStep.LETTERBOX_RESET, TEARDOWN_BUDGET_LETTERBOX_MS) {
            try { resetLetterboxStyle() } catch (e: Exception) {
                err("[teardown-display] reset-letterbox-style failed: ${e.message}")
            }
        },
        TeardownStepPlan(TeardownStep.ROTATION_FREE, TEARDOWN_BUDGET_ROTATION_MS) {
            if (displayId >= 0) {
                try { shell.exec("wm user-rotation -d $displayId free") } catch (_: Exception) {}
            }
        },
        // 2. 先把前台 app 搬回，再把 VD 上**所有**残留 task 搬回并 resize（issue #48）；
        //    只搬顶层会让 quickSwitch 历史任务带着 VD 的 density/bounds 留在物理屏。
        //    issue #199 A：批量迁移 —— 两层合起来只读 2 次 dumpsys、只等 1 个沉降窗口。
        TeardownStepPlan(TeardownStep.APPS_MOVED, TEARDOWN_BUDGET_APPS_MOVED_MS) {
            carCommands.moveTopApp(displayId, 0, probe = false)
            carCommands.moveAllTasksToDisplay(displayId, 0) { remaining ->
                teardownPendingTasks = remaining.joinToString(prefix = "[", postfix = "]")
            }
        },
        // 3. Restore screen BEFORE killing shell (order matters: shell.exec needs the shell alive)
        TeardownStepPlan(TeardownStep.PANEL_RESTORED, 1_500L) {
            displayController.restorePanel()
            try { shell.exec("input keyevent 224") } catch (_: Exception) {}
        },
        TeardownStepPlan(TeardownStep.IME_RESTORED, 1_000L) { displayController.restoreIme() },
        // 3b. issue #48 取证：面板/IME 已恢复后再读快照，避免 dumpsys 拖慢恢复顺序。
        // 只读：显示 display#0 的 init/cur 与仍留在 VD 上的 task id，不改任何状态。
        //
        // PR #208 评审：这一步**依赖** apps-moved。搬迁超时后快照读到的是竞态数据，
        // 而且被放弃的搬迁线程还在用 shell/dumpsys，再插一次 dumpsys 只会互相拖慢。
        TeardownStepPlan(TeardownStep.DISPLAY_SNAPSHOT, 1_200L, dependsOn = TeardownStep.APPS_MOVED) {
            carCommands.logTeardownDisplaySnapshot(displayId)
        },
        // 4. Stop the LifeWriter thread before killing the shell — it may be mid-write.
        TeardownStepPlan(TeardownStep.LIFECYCLE_STOPPED, 600L) { lifecycle.stop() },
        // 4b. 屏障（2026-10-10，MI 9 实测）：上面的恢复命令是 fire-and-forget 写进
        // 常驻 sh 的 stdin，而 close() 的 destroy() 会立刻 SIGTERM 掉它 —— 最后
        // 几条命令还没被 sh 读走就随进程死了。实测丢的正是
        // `settings put system screen_off_timeout 60000`：日志完整显示"恢复成功 +
        // Cleanup complete"，但会话结束后值仍停在 2147483647 哨兵。echo 一个唯一
        // 标记并等排水线程回显（stdin/stdout 双 FIFO）⇒ 此前命令都已执行完。
        TeardownStepPlan(TeardownStep.SHELL_DRAINED, 1_200L) { shell.awaitIdle() },
        // Now kill the shell
        TeardownStepPlan(TeardownStep.SHELL_CLOSED, 600L) { shell.close() },
        // S3-PA（issue #73）决策 4：先停音频、再停视频。
        //
        // 音频必须在 signalPipelineStop() 之前关：AudioPipeline.close() 自己有 1.5s 的有界
        // join（AudioPipeline.CLOSE_JOIN_TIMEOUT_MS），与视频那条 2s 预算串联而非叠加在同一
        // 段里，cleanup 的总耗时仍可控。放在之前还保证采集线程先停，不会在 VD/编码器已经
        // 拆掉的窗口里继续读 REMOTE_SUBMIX。
        //
        // 顺序是「先采集、后写端」：采集停掉之后不会再有 onFrame 进来，此时关 sink 才能让
        // 写线程排空退出并真正关掉 9640 的 socket（sink 是 channel 的唯一属主）。
        TeardownStepPlan(TeardownStep.AUDIO_CLOSED, 2_200L) {
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
        },
        // S-M8/S-06: ask the pipeline thread to exit (running=false + unpark) and wait for it
        // (bounded) BEFORE touching the encoder — the render loop may still be inside a
        // native dequeueOutputBuffer/eglSwapBuffers call, which is what used to race
        // cleanup() here.
        TeardownStepPlan(TeardownStep.PIPELINE_JOINED, PIPELINE_JOIN_TIMEOUT_MS + 500L) {
            signalPipelineStop()
        },
        TeardownStepPlan(TeardownStep.ENCODER_RELEASED, 800L) {
            encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        },
        // S-M8: the encoder's input Surface used to be leaked — releasing the codec does not
        // release the Surface we handed it, so every session burned one for nothing.
        TeardownStepPlan(TeardownStep.ENCODER_SURFACE_RELEASED, 400L) {
            encoderSurface?.let { try { it.release() } catch (_: Exception) {} }
        },
        // S-M8: EGL/GL is torn down by the pipeline thread itself (releaseGlResources);
        // signalPipelineStop above is how this thread asks for it.
        TeardownStepPlan(TeardownStep.GL_RELEASED, 1_000L) { releaseGlResources() },
        TeardownStepPlan(TeardownStep.VIRTUAL_DISPLAY_RELEASED, 1_000L) {
            // PR #208 评审：这一步**故意不挂** apps-moved 依赖 —— 泄漏 VD 的代价高于与
            // 残留搬迁线程竞争（它只会多发几条 `am display move-stack`，失败已由 shell 层
            // 兜住）。但必须让日志说清楚"现在是在有残留线程的情况下释放的"。
            if (abandonedStep == TeardownStep.APPS_MOVED) {
                err(
                    "[teardown-step] virtual-display-released runs while the abandoned apps-moved " +
                        "thread may still be moving tasks (${teardownPendingTasks.ifBlank { "[]" }})"
                )
            }
            virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }
        },
        // issue #180：撤掉本会话发布在共享存储上的 pid / token，避免"上一轮标识"被下一轮
        // 的停止方读到（同时只在内容仍属于本进程时才删，见 removeSessionFiles）。
        TeardownStepPlan(TeardownStep.COMPLETE, 500L) { removeSessionFiles() },
    )

    private fun cleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        try {
            // 所有退出判据（reader / pipeline / watchdog）都只看 running，必须先翻。
            running = false
            val report = TeardownRunner(
                steps = buildTeardownPlan(),
                totalBudgetMs = TEARDOWN_TOTAL_BUDGET_MS,
                // issue #165 的诊断开关保持有效：DILINK_TEARDOWN_TRACE=0 只关普通步履行，
                // 异常/超时/预算行仍走 err（那是现场必需的）。
                log = { if (TEARDOWN_TRACE_ENABLED) log(it) },
                err = { err(it) },
                pendingDetail = {
                    teardownPendingTasks.takeIf { it.isNotBlank() }?.let { "remainingTasks=$it" } ?: ""
                },
                onStepStart = { number, total, step -> teardownProgress = "$number/$total ${step.label}" },
                // PR #208 评审：让后续步骤知道"前序步被放弃了"。目前只有一条硬编码依赖
                // （DISPLAY_SNAPSHOT -> APPS_MOVED），这里记录最近一次被放弃的步骤，供
                // VD 释放时告警。
                onStepFinished = { step, outcome ->
                    if (outcome != TeardownOutcome.COMPLETED) abandonedStep = step
                }
            ).run()
            if (report.incomplete) {
                // issue #199 C：明确标记"这次收尾没走完"，下一次启动据此做补偿清理。
                markCleanupIncomplete("teardown")
                log(
                    "Cleanup incomplete (${report.elapsedMs}ms, first-bad=" +
                        "${report.firstBadStep?.label ?: "<none>"})"
                )
            } else {
                log("Cleanup complete")
            }
        } catch (e: Throwable) {
            // 编排器内部已逐步兜异常；这里只兜"规划/收尾本身"出错。cleanupFinished
            // 必须在 finally 里置位，否则 watchdog 会把它当成"挂死"直接 halt。
            err("Cleanup: ${e.describe()}")
        } finally {
            cleanupFinished.set(true)
            cleanupDone.countDown()
        }
    }

    /**
     * issue #199：主线程有界等待收尾落地（见 `run()` 的 finally）。
     *
     * 为什么必须等：`cleanup()` 的三个入口（Lifecycle reader、TouchReader、main）里，前两个
     * 是 daemon 线程（`daemonThread`），而 main 抢不到 `cleanupStarted` 时会立刻返回 ——
     * JVM 随后拆掉所有 daemon，收尾被就地杀死在 `virtual-display-released` 与
     * `removeSessionFiles()` 之间。现场 2026-11-16 整份日志 0 条 `Cleanup complete`
     * 就是这么来的（`.tmp/run-1116/vd-server.log` 的 245/384/802/1067/1693 行全停在 17/18）。
     */
    private fun awaitCleanupFinished(timeoutMs: Long) {
        if (cleanupFinished.get()) return
        try {
            cleanupDone.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!cleanupFinished.get()) {
            err(
                "Cleanup still not finished after ${timeoutMs}ms " +
                    "(at ${teardownProgress.ifBlank { "<unknown>" }}) — exiting anyway"
            )
        }
    }

    /**
     * issue #199 C：把"这次收尾没走完"落盘（[VdDeploy.CLEANUP_INCOMPLETE_PATH]）。
     *
     * 一行文本，含 pid / displayId / 停在哪一步 / 还没搬回的 task：下一次启动的
     * [compensateIncompleteCleanup] 靠它定位，而不是再从"面板没恢复"倒推。
     * 写失败不影响收尾（只是少了线索）。
     */
    private fun markCleanupIncomplete(reason: String) {
        val line = "pid=$sessionPid display=$displayId step=${teardownProgress.ifBlank { "<unknown>" }} " +
            "remainingTasks=${teardownPendingTasks.ifBlank { "[]" }} reason=$reason at=${System.currentTimeMillis()}"
        if (!writeTextAtomically(VdDeploy.CLEANUP_INCOMPLETE_PATH, line)) {
            err("[teardown-recover] failed to write $reason marker")
        }
    }

    /**
     * issue #199 C：上一次收尾没走完时的补偿清理（清场之后、建屏之前）。
     *
     * 被 watchdog `Runtime.halt(1)` 强杀的会话会留下 `cleanup-incomplete`；标记里记着停在
     * 哪一步、哪些 task 还没搬回。这里只做两件事：把线索打进本会话日志（回签/现场第一眼
     * 就能看到上一轮欠了什么），以及 best-effort 把仍挂在旧 VD 上的 task 搬回物理屏。
     *
     * 标记**只有搬迁返回 true 时才删**（PR #208 评审）：搬迁失败/预算不够时留着，下一轮
     * 启动再试 —— 否则"补偿"两个字就只是日志，任务照样滞留在已销毁的 display 上。
     */
    private fun compensateIncompleteCleanup() {
        val f = File(VdDeploy.CLEANUP_INCOMPLETE_PATH)
        if (!f.exists()) return
        val detail = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        err("[teardown-recover] previous cleanup incomplete: ${detail.ifBlank { "<empty>" }}")
        val stale = Regex("display=(-?\\d+)").find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        // 标记里没有合法 display id 时没有可补偿的动作，直接清掉。
        val moved = if (stale >= 0) carCommands.moveAllTasksToDisplay(stale, 0) else true
        if (!moved) {
            err(
                "[teardown-recover] W: compensation failed (staleDisplay=$stale, " +
                    "moveAllTasksToDisplay=false：搬迁未完成或被预算截断) — marker kept for next session"
            )
            return
        }
        val removed = runCatching { f.delete() }.getOrDefault(false)
        err("[teardown-recover] compensation done (staleDisplay=$stale, markerDeleted=$removed)")
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

/**
 * issue #165：`cleanup()` 的阶段序列（诊断 + 顺序契约）。
 *
 * FORTIFY 现场只有一行地址、没有调用栈。`[teardown-step]` 把收尾切成可对账的阶段：
 * 下一次现场只要看 FORTIFY 落在哪个 step 之后，就能把「destroyed mutex」收窄到那一段
 * 的 release 操作。枚举顺序同时是可执行版约定 —— 设备恢复在前、线程停止在中间、
 * native 对象释放在最后（`virtualDisplay.release()` 仍是最后一步）。
 */
internal enum class TeardownStep(val label: String) {
    BEGIN("begin"),
    WINDOW_MONITOR_STOPPED("window-monitor-stopped"),
    LETTERBOX_RESET("letterbox-reset"),
    ROTATION_FREE("rotation-free"),
    APPS_MOVED("apps-moved"),
    PANEL_RESTORED("panel-restored"),
    IME_RESTORED("ime-restored"),
    DISPLAY_SNAPSHOT("display-snapshot"),
    LIFECYCLE_STOPPED("lifecycle-stopped"),
    SHELL_DRAINED("shell-drained"),
    SHELL_CLOSED("shell-closed"),
    AUDIO_CLOSED("audio-closed"),
    PIPELINE_JOINED("pipeline-joined"),
    ENCODER_RELEASED("encoder-released"),
    ENCODER_SURFACE_RELEASED("encoder-surface-released"),
    GL_RELEASED("gl-released"),
    VIRTUAL_DISPLAY_RELEASED("virtual-display-released"),
    COMPLETE("complete")
}

/**
 * issue #165：cleanup 阶段必须单调前进（纯函数，单测见 `TeardownOrderTest`）。
 *
 * 返回 false 表示顺序被改坏（跳回/重复）。`cleanup()` 对它只记一行 err、**不中断收尾** ——
 * 面板恢复与 VD 释放的优先级高于日志正确性。
 */
internal fun isTeardownStepForward(current: TeardownStep?, next: TeardownStep): Boolean =
    current == null || next.ordinal > current.ordinal

/**
 * issue #180：watchdog 武装前后的启动编排 —— **清场 → 武装 → 建屏**，顺序不能交换。
 *
 * 抽成 top-level 函数是为了让纯 JVM 单测能锁住这条顺序契约（[PipelineServer]
 * 依赖 Android 运行环境，进不了单测）：
 *  - 清场必须在 arm 之前，否则 arm 之后、清场之前落盘的旧会话哨兵会被当成自己的；
 *  - 建屏必须在 arm 之后，否则建屏期（EGL/编码器/VD，上限 10s）里没人消费真停止
 *    请求，用户按下的停止会被拖到建屏结束才生效。
 *
 * 清场本身见 `PipelineServer.clearStartupStopRequest`，建屏见
 * `PipelineServer.createVirtualDisplay`。
 */
internal fun runStartupSequence(
    clearStartupStopRequest: () -> Unit,
    armWatchdog: () -> Unit,
    createVirtualDisplay: () -> Boolean
): Boolean {
    clearStartupStopRequest()
    armWatchdog()
    return createVirtualDisplay()
}

/**
 * 是否是「别人会话的哨兵文件」—— 启动清场的删除集合（issue #180）。
 *
 * 只认 `stop-request-<token>` 形态，并且：
 *  - 排除本会话自己的文件（它还可能是初始化窗口里的真停止）；
 *  - 排除写入过程中的**临时文件**：停止方是"同目录 temp + mv"原子写
 *    （`stop-request-<token>.tmp.<pid>`），并发写入时那是别人正在用的中转文件，
 *    整批删掉会把一次真停止请求抹掉（第四轮复审非阻塞项 1）。
 */
internal fun isOtherSessionSentinelName(name: String, ownName: String): Boolean {
    if (name == ownName) return false
    if (!name.startsWith("${VdDeploy.STOP_REQUEST_PREFIX}-")) return false
    if (name.contains(".tmp")) return false
    return true
}
