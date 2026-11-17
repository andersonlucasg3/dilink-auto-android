package com.dilinkauto.server.service

import android.content.res.Resources
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.VideoConfig
import com.dilinkauto.protocol.vdRunDeploySequence
import com.dilinkauto.server.R
import com.dilinkauto.server.adb.RemoteAdbController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns deployment of the vd-server ([com.dilinkauto.vdserver.PipelineServer])
 * onto the phone from the car, via whichever ADB transport is currently
 * available (USB or TCP), plus the retry/fallback policy around it.
 *
 * Extracted from [CarConnectionService] so the connection state machine does
 * not own deploy logic, the retry counter, or the kill/launch command
 * assembly. Decoupled from CarConnectionService (SRP/DRY audit R3-SRP-03)
 * via explicit narrow collaborators instead of wide host coupling.
 *
 * The kill → wait-for-exit → launch orchestration itself lives in
 * [vdRunDeploySequence] (protocol-core), shared with the phone's Shizuku path
 * and the desktop adb.exe path. This class only supplies:
 *  - the two entry points ([deploy] / [deployDirect]) and their retry policy;
 *  - a [VdDeployExecutor] implementation per transport;
 *  - the plan assembly (viewport + DPI) and status-message sequencing.
 */
internal class VdServerDeployer(
    private val resources: Resources,
    private val scope: CoroutineScope,
    private val carPrefs: CarPrefs,
    private val adbTrackerProvider: () -> CarAdbTracker,
    private val sessionCoordinatorProvider: () -> CarSessionCoordinator,
    private val setStatusMessage: (Int, Array<out Any>) -> Unit,
    private val logSink: (String, String) -> Unit,
) {
    private var deployRetries = 0

    private val adbTracker: CarAdbTracker get() = adbTrackerProvider()
    private val sessionCoordinator: CarSessionCoordinator get() = sessionCoordinatorProvider()

    private fun log(msg: String, level: String = "I") = logSink(msg, level)
    private fun setStatus(resId: Int, vararg args: Any) = setStatusMessage(resId, args)

    /**
     * Deploy via whichever ADB transport is currently reported as ready.
     * Retries twice on transient "no ADB" then falls back to TCP-ADB using
     * the phone-host IP if known.
     */
    fun deploy() {
        if (sessionCoordinator.vdServerStarted) return
        if (!adbTracker.isAdbAvailable()) {
            log("deployVdServer: no ADB connection (retry=$deployRetries)")
            if (deployRetries < 2) {
                deployRetries++
                scope.launch(Dispatchers.IO) {
                    delay(500)
                    deploy()
                }
                return
            }
            adbTracker.noAdbCount++
            // Auto-fallback: try TCP ADB if we have the phone IP
            val phoneHost = sessionCoordinator.phoneHost
            if (phoneHost != null && !carPrefs.devMode) {
                log("Auto-fallback: trying TCP ADB to $phoneHost:${Ports.ADB_PORT}")
                setStatus(R.string.status_connecting_tcp_adb, phoneHost)
                scope.launch(Dispatchers.IO) {
                    adbTracker.connectTcpAdb(phoneHost)
                    if (adbTracker.adbController?.isConnected == true) {
                        log("Auto-fallback TCP ADB connected — deploying VD server")
                        deploy()
                    } else {
                        setStatus(R.string.status_no_adb)
                        log("Auto-fallback TCP ADB failed — connect phone to car USB", "W")
                    }
                }
            } else if (phoneHost == null) {
                setStatus(R.string.status_no_phone_ip)
            } else {
                setStatus(R.string.status_no_adb)
            }
            return
        }
        sessionCoordinator.vdServerStarted = true  // Set early to prevent duplicate deploys
        scope.launch(Dispatchers.IO) {
            runSequence(adbExecutor(), sessionCoordinator.vdServerJarPath)
        }
    }

    /**
     * Deploy using a known-good [controller] (the dev-mode TCP-ADB path, which
     * already has a fresh controller in hand). Bypasses the availability probe
     * because the controller is connected by construction.
     */
    suspend fun deployDirect(controller: RemoteAdbController) {
        if (sessionCoordinator.vdServerStarted) return
        sessionCoordinator.vdServerStarted = true  // Set early to prevent duplicate deploys
        runSequence(controllerExecutor(controller), VdDeploy.JAR_PATH)
    }

    /**
     * Shared body of both entry points: build the plan, run the standardized
     * kill → wait → force-kill-if-needed → launch sequence, and drive the
     * status messages. Failure re-opens [sessionCoordinator.vdServerStarted]
     * so a later state-machine pass may retry.
     */
    private suspend fun runSequence(executor: VdDeployExecutor, jarPath: String) {
        val plan = buildPlan(jarPath)
        setStatus(R.string.status_preparing_vd)
        val outcome = vdRunDeploySequence(plan, executor)
        setStatus(R.string.status_starting_vd)
        if (!outcome.launched) {
            log("VD server failed to start", "E")
            setStatus(R.string.status_vd_failed)
            sessionCoordinator.vdServerStarted = false  // Allow retry
            // The launch command failed at the transport layer (half-open TCP ADB
            // socket after a phone-side WiFi flap — see RemoteAdbController's class
            // doc). Drop the controller so the next state pass rebuilds it instead
            // of reusing the dead socket (S05 live evidence, 2026-10-17).
            adbTracker.resetAdbController()
            return
        }
        setStatus(R.string.status_waiting_video)
        log("VD server started, waiting for video")
    }

    /** Viewport + DPI resolution, shared by both entry points. */
    private fun buildPlan(jarPath: String): VdDeploy.DeployPlan {
        val displayMetrics = resources.displayMetrics
        val vp = CarViewport.size(
            displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density
        )
        val vdW = vp.first
        val vdH = vp.second
        val phoneDpi = if (sessionCoordinator.handshakeVdDpi > 0) sessionCoordinator.handshakeVdDpi
            else VideoConfig.calculateOptimalDpi(vdW, vdH, displayMetrics.densityDpi)
        log("VD server: ${vdW}x${vdH}@${phoneDpi}dpi (car-native, no downscale)")
        return VdDeploy.buildDeployPlan(
            jarPath = jarPath,
            logPath = VdDeploy.LOG_PATH,
            vdWidth = vdW, vdHeight = vdH, dpi = phoneDpi,
            encodeWidth = vdW, encodeHeight = vdH,
            phoneHost = "127.0.0.1", fps = sessionCoordinator.targetFps,
            bitrate = carPrefs.startupBitrate,
            // background=false → exec app_process，shell 流保持附着：
            // 带 & 后台化会让 shell 立即退出、ADB 流立刻关闭，而车机端
            // TcpAdbConnection 没有按流分发的读线程，残留消息会连带
            // 干掉刚启动的引擎（表现为日志 0 字节、进程秒死）。
            background = false
        )
    }

    // ─── Transports ───

    /** USB / local-ADB executor — commands go through the host's active transport. */
    private fun adbExecutor() = object : VdDeployExecutor {
        override suspend fun shellSync(command: String) {
            adbTracker.executeAdb(command, noWait = false)
        }

        override suspend fun launch(command: String): Boolean =
            adbTracker.executeAdb(command, noWait = true)

        override suspend fun probe(): VdProbeResult = probeViaHost()
    }

    /**
     * Dev-mode TCP-ADB executor — kill/launch go through the freshly established
     * [controller] (known-good by construction).
     */
    private fun controllerExecutor(controller: RemoteAdbController) = object : VdDeployExecutor {
        override suspend fun shellSync(command: String) {
            controller.shell(command)
        }

        override suspend fun launch(command: String): Boolean =
            controller.shellBackground(command) >= 0

        override suspend fun probe(): VdProbeResult = probeViaHost()
    }

    /**
     * Liveness probe via the host's currently-active ADB transport — this is
     * what the previous `waitForVdServerExit()` used on all car paths, so the
     * probe semantics are unchanged by the extraction.
     *
     * A `false` result means "probe exited non-zero" — gone when a transport is
     * up, but [VdProbeResult.UNKNOWN] when the transport itself died (`executeAdb`
     * documents `false` for an unavailable transport), so the sequence accepts
     * the exit instead of stalling the deploy.
     */
    private fun probeViaHost(): VdProbeResult {
        val alive = adbTracker.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)
        return when {
            alive -> VdProbeResult.ALIVE
            !adbTracker.isAdbAvailable() -> VdProbeResult.UNKNOWN
            else -> VdProbeResult.GONE
        }
    }

    /** Reset retry/state on a fresh connect cycle. */
    fun reset() {
        deployRetries = 0
    }
}
