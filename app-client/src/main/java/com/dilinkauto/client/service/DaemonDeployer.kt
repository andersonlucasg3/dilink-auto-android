package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import java.io.File
import java.util.zip.CRC32

/**
 * Stages the native VD daemon (dilinkd) assets for the car-side ADB deploy.
 *
 * Used by ConnectionService (car flow — the car deploys the daemon over ADB
 * from the staged /sdcard/DiLinkAuto files).
 */
object DaemonDeployer {

    private const val TAG = "DaemonDeployer"

    /**
     * Extract vd-server.jar + libdilinkd.so to the app-private dir and to
     * /sdcard/DiLinkAuto for the car ADB deploy path.
     */
    fun ensureAssets(context: Context) {
        extractAsset(context, "vd-server.jar", File(context.filesDir, "vd-server.jar"))
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        extractAsset(context, "native/${abi}/libdilinkd.so", File(context.filesDir, "libdilinkd.so"))

        // /sdcard staging is only used by the car ADB deploy; without
        // All Files access it simply fails and that path stays unavailable.
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
        dir.mkdirs()
        extractAsset(context, "vd-server.jar", File(dir, "vd-server.jar"))
        try {
            extractAsset(context, "native/${abi}/libdilinkd.so", File(dir, "libdilinkd.so"))
        } catch (e: Exception) {
            FileLog.w(TAG, "Native lib not bundled for $abi: ${e.message}")
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
