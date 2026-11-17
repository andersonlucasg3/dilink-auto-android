package com.dilinkauto.client.service

import com.dilinkauto.client.FileLog
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdDeployOutcome
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.VideoConfig
import com.dilinkauto.protocol.vdRunDeploySequence
import java.io.File

/**
 * Handles assembling and executing the VD server deployment plan via Shizuku.
 *
 * Extracted from [ConnectionService] (SRP): encapsulates Shizuku shell commands,
 * directory preparation, VdDeployPlan assembly, and deploy sequence execution.
 */
object ShizukuVdDeployer {

    private const val TAG = "ShizukuVdDeployer"

    /**
     * Deploys and launches the VD server process using Shizuku privileges.
     *
     * @return [VdDeployOutcome] of the deployment sequence, or null if Shizuku was unavailable.
     */
    suspend fun deploy(
        carWidth: Int,
        carHeight: Int,
        vdWidth: Int,
        vdHeight: Int,
        dpi: Int = VideoConfig.DEFAULT_FALLBACK_DPI,
        targetFps: Int,
        targetBitrate: Int,
        carHost: String = VdDeployArgs.CAR_HOST_ANY,
        allowedPackages: Collection<String> = emptyList(),
        /** issue #202 A：DPI 来源令牌，透传到引擎启动行（`-` = 不上报）。 */
        dpiSource: String = "-",
        jarPath: String = VdDeploy.JAR_PATH,
        logPath: String = VdDeploy.LOG_PATH,
        dirPath: String = VdDeploy.DIR_PATH,
        executorOverride: VdDeployExecutor? = null
    ): VdDeployOutcome? {
        if (!ShizukuManager.isAvailable && executorOverride == null) {
            FileLog.w(TAG, "Shizuku not available — cannot start VD server")
            return null
        }

        try {
            val dir = File(dirPath)
            if (!dir.exists()) dir.mkdirs()
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to create directory $dirPath: ${e.message}")
        }

        val plan = VdDeploy.buildDeployPlan(
            jarPath = jarPath,
            logPath = logPath,
            vdWidth = vdWidth,
            vdHeight = vdHeight,
            dpi = dpi,
            encodeWidth = carWidth,
            encodeHeight = carHeight,
            phoneHost = "127.0.0.1",
            fps = targetFps,
            bitrate = targetBitrate,
            background = true,
            carHost = carHost,
            allowedPackages = allowedPackages,
            dpiSource = dpiSource
        )

        val executor = executorOverride ?: object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                ShizukuManager.execAndWait(command)
            }

            override suspend fun launch(command: String): Boolean {
                return ShizukuManager.execBackground(command)
            }

            override suspend fun probe(): VdProbeResult = ShizukuManager.probeVdServer()
        }

        return vdRunDeploySequence(plan, executor)
    }
}
