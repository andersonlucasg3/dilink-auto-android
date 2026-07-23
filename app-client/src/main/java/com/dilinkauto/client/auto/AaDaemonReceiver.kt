package com.dilinkauto.client.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.PrivilegeRouter
import com.dilinkauto.protocol.AaBridge
import kotlin.concurrent.thread

/**
 * Receives the daemon's IAaDaemon binder via explicit broadcast (the only
 * app-reachable channel — custom ServiceManager services are invisible to
 * untrusted_app). Validates the anti-spoof token when a privileged backend
 * can cross-check it.
 */
class AaDaemonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AaBridge.ACTION_ANNOUNCE) return
        // getIBinderExtra is @hide in the SDK — read the raw extra value instead
        val binder = intent.extras?.get(AaBridge.EXTRA_BINDER) as? IBinder ?: return
        val token = intent.getStringExtra(AaBridge.EXTRA_TOKEN)

        val pending = goAsync()
        thread(name = "AaDaemonRx") {
            try {
                if (!validateToken(token)) {
                    FileLog.w(TAG, "Daemon announce rejected: bad token")
                    return@thread
                }
                FileLog.i(TAG, "Daemon announce received — connecting")
                AaDaemonClient.onDaemonBinder(binder)
            } finally {
                pending.finish()
            }
        }
    }

    private fun validateToken(token: String?): Boolean {
        // Emulator/dev path: no privileged backend to cross-check against
        if (!PrivilegeRouter.isAvailable) return true
        val expected = PrivilegeRouter.execAndWait("cat ${AaBridge.TOKEN_FILE} 2>/dev/null")?.trim()
        if (expected.isNullOrEmpty()) {
            FileLog.w(TAG, "Token file unreadable — cannot validate daemon")
            return false
        }
        return expected == token
    }

    private companion object {
        private const val TAG = "AaDaemonReceiver"
    }
}
