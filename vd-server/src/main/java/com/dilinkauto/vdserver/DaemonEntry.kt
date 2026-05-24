package com.dilinkauto.vdserver

import android.os.Looper

/**
 * Entry point for the native streaming daemon.
 *
 * Launched via Shizuku or ADB:
 *   CLASSPATH=bridge.jar app_process / com.dilinkauto.vdserver.DaemonEntry <args>
 *
 * Loads libdilinkd.so and delegates to nativeRun() which runs the full
 * C++ pipeline: VirtualDisplay → EGL/GLES → AMediaCodec → TCP streaming.
 *
 * The Java side is minimal — just the bridge for Java-only APIs
 * (VirtualDisplay creation, input injection, display power, shell commands).
 */
object DaemonEntry {

    init {
        // Try deployed path first (alongside JAR), then standard path
        try {
            System.load("/sdcard/DiLinkAuto/libdilinkd.so")
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.load("/data/local/tmp/libdilinkd.so")
            } catch (e2: UnsatisfiedLinkError) {
                System.loadLibrary("dilinkd")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Ensure main looper for FakeContext
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val bridge = NativeBridge()
        val result = nativeRun(args, bridge)
        if (result != 0) {
            System.err.println("[Daemon] nativeRun exited with code $result")
        }
    }

    /** Native pipeline entry point. Blocks until streaming stops. */
    @JvmStatic
    private external fun nativeRun(args: Array<String>, bridge: NativeBridge): Int
}
