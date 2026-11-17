package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.protocol.Ports
import java.io.File

/**
 * Drives one car-app install: locate the car, connect over ADB, skip if already
 * current, otherwise push and install.
 *
 * Extracted from [ConnectionService.installCarApp]
 * (docs/audit-srp-dry.md SRP-2). That method was ~70 lines of sequencing that
 * belonged to no single connection concern — it neither opens nor tears down the
 * streaming connection, it just runs alongside it and reports progress.
 *
 * Status is reported through a callback rather than a `StateFlow` so this class
 * has no opinion about how the UI observes it; the service keeps owning
 * `_installStatus`.
 */
internal class CarInstallCoordinator<S>(
    private val context: Context,
    private val apkFile: File,
    private val installer: CarInstaller<S>,
    /** Supplies the streaming connection's remote IP, or null when not connected. */
    private val connectedCarIp: () -> String?,
    /** Clears the car's icon cache after a reinstall. */
    private val onReinstalled: () -> Unit,
    private val status: (String) -> Unit,
    /** Resolves the car IP to install against. Injected for tests; defaults to the real probe/scan. */
    private val resolveCarIp: suspend (String?) -> String? = { explicitIp ->
        if (!explicitIp.isNullOrBlank()) {
            if (CarIpLocator.probePortSync(explicitIp, Ports.ADB_PORT)) explicitIp
            else {
                status(context.getString(R.string.car_install_status_not_reachable, explicitIp)
                    ?: "res:${R.string.car_install_status_not_reachable}($explicitIp)")
                null
            }
        } else CarIpLocator.findCarAdb(connectedCarIp())
    },
    /** The phone's embedded version label. Injected for tests; defaults to AppVersion.label(context). */
    private val myVersionName: () -> String = { AppVersion.label(context) }
) {

    /**
     * Runs the install flow off the caller's thread.
     *
     * @param explicitIp a manually entered IP to try first; null means search.
     */
    suspend fun install(explicitIp: String? = null) {
        // Keep the final status on screen when the failure needs user action
        // (e.g. waiting for ADB authorization), instead of clearing it.
        var keepStatus = false
        try {
            if (!apkFile.exists()) {
                status(statusString(R.string.car_install_status_car_apk_not_found))
                FileLog.w(TAG, "No embedded car APK")
                return
            }

            status(
                if (explicitIp != null) statusString(R.string.car_install_status_connecting_to, explicitIp)
                else statusString(R.string.car_install_status_searching)
            )
            val carIp = resolveCarIp(explicitIp) ?: run {
                status(statusString(R.string.car_install_status_car_not_found))
                FileLog.w(TAG, "Could not find car ADB on USB or network")
                return
            }

            status(statusString(R.string.car_install_status_connecting_to, carIp))
            FileLog.i(TAG, "Connecting to car ADB at $carIp:${Ports.ADB_PORT}")

            val session = installer.connect(carIp)
            if (session == null) {
                // Needs the user to approve the ADB prompt on the car; hold the
                // message instead of clearing it after the delay below.
                status(statusString(R.string.car_install_status_auth_needed))
                keepStatus = true
                return
            }
            FileLog.d(TAG, "Dadb.create() succeeded")

            try {
                status(statusString(R.string.car_install_status_checking_version))
                val installedVersionName = installer.readInstalledVersion(session)
                val myVersionName = myVersionName()
                FileLog.i(TAG, "Car app: installed=$installedVersionName, embedded=$myVersionName")

                if (compareVersions(myVersionName, installedVersionName) <= 0) {
                    status(statusString(R.string.car_install_status_already_up_to_date, installedVersionName))
                    return
                }

                val result = installer.pushAndInstall(session, apkFile, myVersionName)
                FileLog.i(TAG, "Install result: ${result.trim()}")

                if (result.contains("Success")) {
                    onReinstalled()
                    status(statusString(R.string.car_install_status_car_installed, myVersionName))
                } else {
                    status(statusString(R.string.car_install_status_failed, result.trim()))
                }
            } finally {
                installer.close(session)
            }
        } catch (e: Exception) {
            status(statusString(R.string.car_install_status_error, e.message ?: "unknown"))
            FileLog.e(TAG, "Car app install failed", e)
        } finally {
            if (!keepStatus) {
                kotlinx.coroutines.delay(5000)
                status("")
            }
        }
    }

    /**
     * Resolve an install status string. Null-safe so the state machine is
     * unit-testable: under the mockable-android jar `context.getString` returns
     * null; production always returns non-null so this is behaviour-identical.
     */
    private fun statusString(res: Int, vararg args: Any?): String =
        context.getString(res, *args) ?: "res:$res(${args.joinToString()})"

    companion object {
        private const val TAG = "CarInstall"
    }
}