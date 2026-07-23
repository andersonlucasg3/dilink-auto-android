package com.dilinkauto.vdserver

import android.os.Binder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Pure-Kotlin AA daemon: announces its IAaDaemon binder to the app via
 * broadcast and serves binder calls (VD on the AA surface, input injection,
 * app launching). No native lib — the H.264 pipeline is only for the car flow.
 */
object AaDaemonMain {

    @JvmStatic
    fun run(): Int {
        val bridge = AaDaemonBridge()
        // Main joins the binder pool immediately so the app's registerAppCallback
        // lands while announce() retries in parallel
        thread(name = "AaAnnounce") {
            if (!bridge.announce()) {
                System.err.println("[AaDaemon] announce failed — exiting")
                exitProcess(1)
            }
        }
        Binder.joinThreadPool()
        return 0
    }
}
