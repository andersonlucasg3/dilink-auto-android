package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.PrivilegeRouter
import java.io.File
import java.util.zip.CRC32

/**
 * Deploys and starts the native VD daemon (dilinkd) with elevated privileges.
 *
 * Shared by ConnectionService (car flow — daemon connects out to the car IP)
 * and the Android Auto flow (daemon connects out to 127.0.0.1). Callers must
 * bind the lifecycle (:19647) and video/input (:9638/:9639) listeners BEFORE
 * start(): the lifecycle connect is single-shot, video/input retry for 30s.
 */
object DaemonDeployer {

    private const val TAG = "DaemonDeployer"

    @Volatile
    private var assetsReady = false

    /** Extract vd-server.jar + libdilinkd.so to /sdcard/DiLinkAuto (CRC-checked, idempotent). */
    fun ensureAssets(context: Context) {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
        dir.mkdirs()
        extractAsset(context, "vd-server.jar", File(dir, "vd-server.jar"))

        // Native lib goes to sdcard for the car ADB deploy path; the privileged
        // start below copies it to /data/local/tmp (sdcard is noexec).
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        try {
            extractAsset(context, "native/${abi}/libdilinkd.so", File(dir, "libdilinkd.so"))
        } catch (e: Exception) {
            FileLog.w(TAG, "Native lib not bundled for $abi: ${e.message}")
        }
        assetsReady = true
    }

    /**
     * Start dilinkd via PrivilegeRouter (root su, or Shizuku shell).
     * Blocking — call from an IO dispatcher. Returns false if no backend/asset failed.
     */
    fun start(context: Context, vdWidth: Int, vdHeight: Int, vdDpi: Int,
              encWidth: Int, encHeight: Int, fps: Int, targetIp: String): Boolean {
        if (!PrivilegeRouter.isAvailable) {
            FileLog.w(TAG, "No privileged backend available — cannot start daemon")
            return false
        }
        return try {
            ensureAssets(context)

            val diLinkDir = File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
            val jarPath = File(diLinkDir, "vd-server.jar").absolutePath
            val logFile = "/sdcard/DiLinkAuto/vd-server.log"
            // Args: W H DPI PHONE_HOST EW EH FPS CAR_IP
            val args = "$vdWidth $vdHeight $vdDpi 127.0.0.1 $encWidth $encHeight $fps $targetIp"

            PrivilegeRouter.execAndWait("pkill -f DaemonEntry 2>/dev/null")
            Thread.sleep(200)

            val cpuAbi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
            val soAssetPath = "native/${cpuAbi}/libdilinkd.so"
            val soTmp = "/data/local/tmp/libdilinkd.so"
            val appSoPath = "${context.filesDir.absolutePath}/libdilinkd.so"
            try {
                val soBytes = context.assets.open(soAssetPath).use { it.readBytes() }
                File(appSoPath).writeBytes(soBytes)
                PrivilegeRouter.execAndWait("cp $appSoPath $soTmp && chmod 644 $soTmp")
                FileLog.i(TAG, "Native .so deployed to $soTmp (${soBytes.size} bytes)")
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to deploy .so via privileged shell: ${e.message}")
            }

            // env sets vars before setsid; & backgrounds so shell exits quickly.
            // execAndWait reads shell's rapid exit, daemon survives via setsid.
            val cmd = "setsid env LD_LIBRARY_PATH=/data/local/tmp " +
                    "CLASSPATH=$jarPath app_process / " +
                    "com.dilinkauto.vdserver.DaemonEntry $args" +
                    " >$logFile 2>&1 &"
            PrivilegeRouter.execAndWait(cmd)
            FileLog.i(TAG, "Native daemon started via ${PrivilegeRouter.displayName}: ${vdWidth}x${vdHeight} -> $targetIp")
            true
        } catch (e: Exception) {
            FileLog.e(TAG, "Daemon start failed", e)
            false
        }
    }

    fun stop() {
        PrivilegeRouter.execAndWait("pkill -f DaemonEntry 2>/dev/null")
    }

    private fun extractAsset(context: Context, assetName: String, target: File) {
        try {
            val assetBytes = context.assets.open(assetName).use { it.readBytes() }
            val assetCrc = CRC32().apply { update(assetBytes) }.value

            if (target.exists()) {
                val fileCrc = CRC32().apply { update(target.readBytes()) }.value
                if (fileCrc == assetCrc) {
                    FileLog.i(TAG, "$assetName up-to-date (crc=$assetCrc)")
                    return
                }
            }

            val tmp = File("${target.absolutePath}.tmp")
            tmp.writeBytes(assetBytes)
            tmp.renameTo(target)
            FileLog.i(TAG, "$assetName deployed to ${target.absolutePath} (${assetBytes.size} bytes, crc=$assetCrc)")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to extract $assetName: ${e.message}")
        }
    }
}
