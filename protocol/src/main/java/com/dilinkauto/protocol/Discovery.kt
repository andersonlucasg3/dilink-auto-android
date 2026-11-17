package com.dilinkauto.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * mDNS-based service discovery for DiLink-Auto.
 *
 * The phone (client) registers a service on the network.
 * The car (server) discovers and connects to it.
 *
 * Service type: _dilinkauto._tcp
 * Default port: 9637
 */
object Discovery {

    const val SERVICE_TYPE = "_dilinkauto._tcp."
    const val SERVICE_NAME = "DiLink-Auto"

    fun isCompatibleServiceName(name: String?): Boolean =
        name != null && name.startsWith(SERVICE_NAME)

    // 协议端口常量的唯一来源是平台无关的 :protocol-core（见 [Ports]）。
    //
    // 下面的别名曾为避免改动"大量历史调用点"而保留；那些调用点现已全部迁移到
    // [Ports]（docs/audit-srp-dry.md DRY-10），别名不再有使用者，故按原计划标注废弃。
    // 保留而未删除，是为了不让下游/未同步的调用方编译失败。
    @Deprecated(
        message = "端口常量的唯一来源是 :protocol-core 的 Ports；请改用 Ports",
        replaceWith = ReplaceWith("Ports.DEFAULT_PORT", "com.dilinkauto.protocol.Ports"),
        level = DeprecationLevel.WARNING
    )
    const val DEFAULT_PORT = Ports.DEFAULT_PORT

    @Deprecated(
        message = "端口常量的唯一来源是 :protocol-core 的 Ports；请改用 Ports",
        replaceWith = ReplaceWith("Ports.VIDEO_PORT", "com.dilinkauto.protocol.Ports"),
        level = DeprecationLevel.WARNING
    )
    const val VIDEO_PORT = Ports.VIDEO_PORT

    @Deprecated(
        message = "端口常量的唯一来源是 :protocol-core 的 Ports；请改用 Ports",
        replaceWith = ReplaceWith("Ports.INPUT_PORT", "com.dilinkauto.protocol.Ports"),
        level = DeprecationLevel.WARNING
    )
    const val INPUT_PORT = Ports.INPUT_PORT

    @Deprecated(
        message = "端口常量的唯一来源是 :protocol-core 的 Ports；请改用 Ports",
        replaceWith = ReplaceWith("Ports.LIFECYCLE_PORT", "com.dilinkauto.protocol.Ports"),
        level = DeprecationLevel.WARNING
    )
    const val LIFECYCLE_PORT = Ports.LIFECYCLE_PORT

    @Deprecated(
        message = "端口常量的唯一来源是 :protocol-core 的 Ports；请改用 Ports",
        replaceWith = ReplaceWith("Ports.ADB_PORT", "com.dilinkauto.protocol.Ports"),
        level = DeprecationLevel.WARNING
    )
    const val ADB_PORT = Ports.ADB_PORT

    /**
     * Registers the DiLink-Auto service on the local network.
     * Call this from the phone (client) side.
     *
     * @return A handle to unregister the service later.
     */
    suspend fun registerService(
        context: Context,
        port: Int = Ports.DEFAULT_PORT,
        deviceName: String = android.os.Build.MODEL
    ): ServiceRegistration = suspendCancellableCoroutine { cont ->
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("device", deviceName)
            setAttribute("version", "1")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (cont.isActive) {
                    cont.resume(ServiceRegistration(nsdManager, this))
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        DiscoveryException("Service registration failed: error $errorCode")
                    )
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        cont.invokeOnCancellation {
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) {}
        }
    }

    /**
     * Discovers DiLink-Auto services on the local network.
     * Call this from the car (server) side.
     *
     * Emits discovered services as a Flow. The discovery runs until the flow is cancelled.
     */
    fun discoverServices(context: Context): Flow<DiscoveredService> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Only trust our own service name (or conflict-renamed instances):
                // NSD's service-type discovery returns every responder advertising
                // `_dilinkauto._tcp`, and an impostor on the same LAN was previously
                // resolved and shown to the user as a connection target (the car has no
                // other authentication, so this is the first gate).
                if (!isCompatibleServiceName(serviceInfo.serviceName)) {
                    android.util.Log.w(
                        "Discovery",
                        "Ignoring foreign mDNS responder: '${serviceInfo.serviceName}'"
                    )
                    return
                }
                // Resolve the service to get IP and port
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        // A resolved port outside the protocol's known range is
                        // another impostor tell — drop instead of offering it.
                        val port = info.port
                        if (port !in 1..65535) {
                            android.util.Log.w("Discovery", "Ignoring service with invalid port: $port")
                            return
                        }
                        val service = DiscoveredService(
                            name = info.serviceName,
                            host = host,
                            port = port,
                            deviceName = info.attributes["device"]
                                ?.let { String(it) } ?: "Unknown"
                        )
                        trySend(service)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Error 4 = FAILURE_ALREADY_ACTIVE — not fatal, just close the flow
                android.util.Log.w("Discovery", "Discovery start failed: error $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {}
        }
    }

    class ServiceRegistration(
        private val nsdManager: NsdManager,
        private val listener: NsdManager.RegistrationListener
    ) {
        fun unregister() {
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) {}
        }
    }

    data class DiscoveredService(
        val name: String,
        val host: String,
        val port: Int,
        val deviceName: String
    )
}

class DiscoveryException(message: String) : Exception(message)
