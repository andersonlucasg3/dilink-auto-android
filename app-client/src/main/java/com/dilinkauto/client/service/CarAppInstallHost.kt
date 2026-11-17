package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.Ipv4Validator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates car-app APK installation requests.
 *
 * Extracted from [ConnectionService] to decouple APK install concurrency,
 * parameter validation, and background execution orchestration from the core
 * streaming service.
 */
internal class CarAppInstallHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectedCarIp: () -> String?,
    private val onReinstalled: () -> Unit,
    private val ensureAssetsReady: suspend () -> Unit = {},
    private val onStatus: (String) -> Unit,
    private val apkFile: File = File(context.filesDir, "app-server.apk"),
    private val installer: CarInstaller<dadb.Dadb> = CarAppInstaller(context) { onStatus(it) },
    private val coordinator: CarInstallCoordinator<*> = CarInstallCoordinator(
        context = context,
        apkFile = apkFile,
        installer = installer,
        connectedCarIp = connectedCarIp,
        onReinstalled = onReinstalled,
        status = onStatus
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** Guard against concurrent install runs. */
    val installInFlight = AtomicBoolean(false)

    /**
     * Triggers installation of the car APK over ADB.
     *
     * Validates explicit IP (if provided), guards against duplicate concurrent
     * install requests, waits for asset readiness, and invokes the underlying
     * [CarInstallCoordinator].
     */
    fun installCarApp(explicitIp: String? = null) {
        val ip = explicitIp?.trim()?.takeIf { it.isNotEmpty() }
        if (ip != null && !Ipv4Validator.isStrict(ip)) {
            onStatus("Invalid IP: $ip")
            FileLog.w(TAG, "installCarApp: rejecting malformed IP '$ip'")
            return
        }
        if (!installInFlight.compareAndSet(false, true)) {
            onStatus("Install already in progress")
            FileLog.i(TAG, "installCarApp: already in progress — ignoring")
            return
        }
        scope.launch(ioDispatcher) {
            try {
                ensureAssetsReady()
                coordinator.install(ip)
            } finally {
                installInFlight.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "CarAppInstallHost"
    }
}
