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
    const val DEFAULT_PORT = 9637  // Control + Data
    const val VIDEO_PORT = 9638    // Video only (phone → car)
    const val INPUT_PORT = 9639    // Input only (car → phone)

    /**
     * Registers the DiLink-Auto service on the local network.
     * Call this from the phone (client) side.
     *
     * @return A handle to unregister the service later.
     */
    suspend fun registerService(
        context: Context,
        port: Int = DEFAULT_PORT,
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
                // Resolve the service to get IP and port
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val service = DiscoveredService(
                            name = info.serviceName,
                            host = info.host?.hostAddress ?: return,
                            port = info.port,
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
