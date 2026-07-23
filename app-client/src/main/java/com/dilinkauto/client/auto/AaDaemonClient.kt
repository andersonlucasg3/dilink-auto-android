package com.dilinkauto.client.auto

import android.os.IBinder
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.aidl.IAaAppCallback
import com.dilinkauto.protocol.aidl.IAaDaemon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side handle to the daemon's IAaDaemon. The binder arrives via broadcast
 * (AaDaemonReceiver); callers block on a latch until it lands.
 */
object AaDaemonClient {

    private const val TAG = "AaDaemonClient"

    @Volatile
    var daemon: IAaDaemon? = null
        private set

    @Volatile
    var displayId = -1
        private set

    var onDisplayReady: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile
    private var daemonLatch = CountDownLatch(1)

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

    /** Called by AaDaemonReceiver with the validated daemon binder. */
    fun onDaemonBinder(binder: IBinder) {
        val d = IAaDaemon.Stub.asInterface(binder) ?: run {
            FileLog.w(TAG, "asInterface failed on announced binder")
            return
        }
        daemon = d
        d.registerAppCallback(appCallback)
        daemonLatch.countDown()
        FileLog.i(TAG, "Connected to daemon bridge")
    }

    /** Blocking — call from an IO dispatcher. Waits for the daemon's announce. */
    fun awaitDaemon(timeoutMs: Long = 15_000): IAaDaemon? {
        daemon?.let { if (it.asBinder().isBinderAlive) return it }
        return if (daemonLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            daemon
        } else {
            FileLog.w(TAG, "Daemon announce not received within ${timeoutMs}ms")
            null
        }
    }

    fun reset() {
        daemon = null
        displayId = -1
        daemonLatch = CountDownLatch(1)
    }
}
