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
        // /data/local/tmp is executable; /sdcard is noexec
        try {
            System.load("/data/local/tmp/libdilinkd.so")
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("dilinkd")
            } catch (e2: UnsatisfiedLinkError) {
                try {
                    System.load("/sdcard/DiLinkAuto/libdilinkd.so")
                } catch (e3: UnsatisfiedLinkError) {
                    // Non-fatal: aa-daemon is pure Kotlin and needs no native lib
                    System.err.println("[Daemon] libdilinkd.so not found — native streaming unavailable")
                }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Ensure main looper for FakeContext
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        if (args.firstOrNull() == "aa-daemon") {
            val code = AaDaemonMain.run()
            println("[Daemon] AA daemon exit=$code")
            return
        }

        if (args.firstOrNull() == "input-injector") {
            val code = InputInjectorMain.run()
            println("[Daemon] input injector exit=$code")
            return
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
