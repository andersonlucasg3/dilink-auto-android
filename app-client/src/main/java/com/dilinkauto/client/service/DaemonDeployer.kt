package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.BuildConfig
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.PrivilegeRouter
import com.dilinkauto.client.RootManager
import com.dilinkauto.client.ShizukuManager
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

    /**
     * Extract vd-server.jar + libdilinkd.so to the app-private dir (no permission
     * needed — privileged deploys stage from here) and, best-effort, to
     * /sdcard/DiLinkAuto for the car ADB fallback path.
     */
    fun ensureAssets(context: Context) {
        extractAsset(context, "vd-server.jar", File(context.filesDir, "vd-server.jar"))
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        extractAsset(context, "native/${abi}/libdilinkd.so", File(context.filesDir, "libdilinkd.so"))

        // /sdcard staging is only used by the car ADB deploy fallback; without
        // All Files access it simply fails and that path stays unavailable.
        // AA_ONLY builds have no car flow (and no MANAGE_EXTERNAL_STORAGE), so
        // skip the EACCES-prone external storage extraction entirely.
        if (!BuildConfig.AA_ONLY) {
            val dir = File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
            dir.mkdirs()
            extractAsset(context, "vd-server.jar", File(dir, "vd-server.jar"))
            try {
                extractAsset(context, "native/${abi}/libdilinkd.so", File(dir, "libdilinkd.so"))
            } catch (e: Exception) {
                FileLog.w(TAG, "Native lib not bundled for $abi: ${e.message}")
            }
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

            // Stage entirely under /data/local/tmp: executable, no All Files
            // access needed (app-private filesDir copied out via privileged shell)
            val jarTmp = "/data/local/tmp/vd-server.jar"
            val soTmp = "/data/local/tmp/libdilinkd.so"
            val logFile = "/data/local/tmp/vd-server.log"
            // Args: W H DPI PHONE_HOST EW EH FPS CAR_IP
            val args = "$vdWidth $vdHeight $vdDpi 127.0.0.1 $encWidth $encHeight $fps $targetIp"

            PrivilegeRouter.execAndWait("pkill -f DaemonEntry 2>/dev/null")
            Thread.sleep(200)

            val files = context.filesDir.absolutePath
            PrivilegeRouter.execAndWait(
                "cp $files/vd-server.jar $jarTmp && chmod 644 $jarTmp && " +
                "cp $files/libdilinkd.so $soTmp && chmod 644 $soTmp"
            )
            FileLog.i(TAG, "Daemon assets staged in /data/local/tmp")

            // env sets vars before setsid; & backgrounds so shell exits quickly.
            // execAndWait reads shell's rapid exit, daemon survives via setsid.
            val cmd = "setsid env LD_LIBRARY_PATH=/data/local/tmp " +
                    "CLASSPATH=$jarTmp app_process / " +
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

    /**
     * Start the pure-Kotlin AA daemon (IAaDaemon on ServiceManager).
     * Runs as shell uid: via `su shell` when root, directly via Shizuku
     * otherwise — root is only the launcher, never the daemon.
     */
    fun startAaDaemon(context: Context): Boolean {
        if (!PrivilegeRouter.isAvailable) {
            FileLog.w(TAG, "No privileged backend available — cannot start AA daemon")
            return false
        }
        // A healthy daemon must not be restarted: pkilling it mid-session
        // DeadObjectExceptions every connected client and starts a crash
        // loop (app FATAL → linkToDeath → VD teardown).
        com.dilinkauto.client.auto.AaDaemonClient.daemon?.asBinder()?.let {
            if (it.isBinderAlive) {
                FileLog.i(TAG, "AA daemon already alive — not restarting")
                return true
            }
        }
        return try {
            ensureAssets(context)
            PrivilegeRouter.execAndWait("pkill -f DaemonEntry 2>/dev/null")
            Thread.sleep(200)

            val files = context.filesDir.absolutePath
            PrivilegeRouter.execAndWait(
                "cp $files/vd-server.jar /data/local/tmp/vd-server.jar && " +
                "chmod 644 /data/local/tmp/vd-server.jar")

            val cmd = "setsid env CLASSPATH=/data/local/tmp/vd-server.jar app_process / " +
                    "com.dilinkauto.vdserver.DaemonEntry aa-daemon" +
                    " >/data/local/tmp/aa-daemon.log 2>&1 &"
            if (RootManager.isAvailable) {
                RootManager.execAndWait("su shell -c '$cmd'")
            } else {
                ShizukuManager.execAndWait(cmd)
            }
            FileLog.i(TAG, "AA daemon started via ${PrivilegeRouter.displayName}")
            true
        } catch (e: Exception) {
            FileLog.e(TAG, "AA daemon start failed", e)
            false
        }
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
