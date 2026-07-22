package com.dilinkauto.client.auto

import android.os.IBinder
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.aidl.IAaAppCallback
import com.dilinkauto.protocol.aidl.IAaDaemon

/**
 * App-side handle to the daemon's IAaDaemon on ServiceManager.
 * getService is @hide — ClientApp relaxes hidden API enforcement at startup.
 */
object AaDaemonClient {

    private const val TAG = "AaDaemonClient"
    private const val SERVICE_NAME = "dilink.auto.daemon"

    @Volatile
    var daemon: IAaDaemon? = null
        private set

    @Volatile
    var displayId = -1
        private set

    var onDisplayReady: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val appCallback = object : IAaAppCallback.Stub() {
        override fun onDisplayReady(id: Int) {
            displayId = id
            FileLog.i(TAG, "Daemon display ready: id=$id")
            onDisplayReady?.invoke(id)
        }

        override fun onError(message: String) {
            FileLog.w(TAG, "Daemon error: $message")
            onError?.invoke(message)
        }
    }

    /** Blocking — call from an IO dispatcher. Retries until the daemon publishes. */
    fun awaitDaemon(timeoutMs: Long = 15_000): IAaDaemon? {
        daemon?.let { if (it.asBinder().isBinderAlive) return it }
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val binder = getService.invoke(null, SERVICE_NAME) as? IBinder
                if (binder != null) {
                    val d = IAaDaemon.Stub.asInterface(binder)
                    daemon = d
                    d.registerAppCallback(appCallback)
                    FileLog.i(TAG, "Connected to daemon bridge")
                    return d
                }
                Thread.sleep(250)
            }
            FileLog.w(TAG, "Daemon bridge not found within ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            FileLog.e(TAG, "awaitDaemon failed", e)
            null
        }
    }

    fun reset() {
        daemon = null
        displayId = -1
    }
}
