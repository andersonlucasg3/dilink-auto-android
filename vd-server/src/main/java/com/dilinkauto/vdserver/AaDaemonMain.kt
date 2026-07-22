package com.dilinkauto.vdserver

import android.os.Binder

/**
 * Pure-Kotlin AA daemon: publishes IAaDaemon on ServiceManager and serves
 * binder calls (VD on the AA surface, input injection, app launching).
 * No native lib — the H.264 pipeline is only used by the car flow.
 */
object AaDaemonMain {

    @JvmStatic
    fun run(): Int {
        val bridge = AaDaemonBridge()
        if (!bridge.publish()) {
            System.err.println("[AaDaemon] publish failed")
            return 1
        }
        System.err.println("[AaDaemon] published ${AaDaemonBridge.SERVICE_NAME}")
        // Serve inbound binder calls forever (linkToDeath on the app callback
        // exits the process when the phone app dies)
        Binder.joinThreadPool()
        return 0
    }
}
