package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.protocol.Ports
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Installs the embedded `app-server.apk` onto the car via ADB-over-WiFi (dadb),
 * with a hard 15-second connect timeout.
 *
 * Extracted from [ConnectionService] (Phase K3 / code-audit 5.3).
 *
 * Key-pair generation and the Dadb-connect-with-timeout pattern live in
 * [AdbKeyUtil].
 *
 * @param context service context — used for `filesDir` (key storage)
 * @param onStatus invoked with user-facing status strings; caller routes them
 *   to `_installStatusStatic`
 */
class CarAppInstaller(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : CarInstaller<Dadb> {

    private val privKey: File = File(context.filesDir, "adbkey")
    private val pubKey: File = File(context.filesDir, "adbkey.pub")

    /**
     * Connects to the car at [carIp]:5555 with the 15s timeout and returns an
     * open [Dadb]. Caller owns [Dadb.close]. Returns null on timeout — the car
     * auth dialog is likely pending.
     */
    override fun connect(carIp: String): Dadb? {
        val keyPair = ensureKeyPair()
        return AdbKeyUtil.dadbCreateWithTimeout(TAG, carIp, CAR_ADB_PORT, keyPair, DADB_TIMEOUT_SECONDS)
    }

    /**
     * Reads the car's currently-installed `app-server` version name, or "0"
     * if not installed. Requires an open [dadb] session.
     */
    override fun readInstalledVersion(dadb: Dadb): String {
        val output = dadb.shell(
            "dumpsys package com.dilinkauto.server 2>/dev/null | grep versionName"
        ).allOutput
        return parseInstalledVersion(output)
    }

    /**
     * Pushes [apkFile] to `/data/local/tmp/app-server.apk`, runs `pm install -r`,
     * and on success restarts the car app. Returns the `pm install` shell output
     * (contains "Success" on a successful install). The caller owns [dadb]
     * lifecycle (close it after).
     */
    override fun pushAndInstall(dadb: Dadb, apkFile: File, versionLabel: String): String {
        val remotePath = "/data/local/tmp/app-server.apk"
        onStatus.invoke(context.getString(R.string.car_install_status_pushing_apk, apkFile.length() / 1024 / 1024))
        dadb.push(apkFile, remotePath)
        FileLog.i(TAG, "Car APK pushed (${apkFile.length()} bytes)")

        onStatus.invoke(context.getString(R.string.car_install_status_installing_version, versionLabel))
        val result = dadb.shell("pm install -r $remotePath").allOutput
        FileLog.i(TAG, "Install result: ${result.trim()}")

        if (result.contains("Success")) {
            onStatus.invoke(context.getString(R.string.car_install_status_launching_car_app))
            dadb.shell("am start --activity-clear-task -n ${com.dilinkauto.protocol.AppTargets.CAR_MAIN_ACTIVITY}")
        }
        return result
    }

    private fun ensureKeyPair(): AdbKeyPair = AdbKeyUtil.ensureAdbKeyPair(context.filesDir)

    override fun close(dadb: Dadb) {
        dadb.close()
    }

    companion object {
        private const val TAG = "CarAppInstaller"
        private const val CAR_ADB_PORT = Ports.ADB_PORT
        private const val DADB_TIMEOUT_SECONDS = 15L

        /**
         * Parse the installed version name out of `dumpsys package` output;
         * "0" (the not-installed sentinel) when absent. Extracted as a pure
         * function so the parsing is unit-testable without a Dadb session.
         */
        internal fun parseInstalledVersion(output: String): String =
            Regex("""versionName=(\S+)""").find(output)?.groupValues?.get(1) ?: "0"
    }
}
