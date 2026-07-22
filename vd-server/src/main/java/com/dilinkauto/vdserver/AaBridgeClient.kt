package com.dilinkauto.vdserver

import android.content.ContentResolver
import android.content.Context
import com.dilinkauto.protocol.aidl.IAaBridge

/**
 * Validates that a shell app_process can reach the phone app's bridge binder
 * via ContentProvider.call (works without an AMS process record, unlike
 * bindService) and make two-way Binder calls — the foundation of the AA
 * surface handoff.
 */
object AaBridgeClient {

    private const val AUTHORITY = "com.dilinkauto.client.aabridge"

    // Concrete ContentResolver bound to FakeContext, so the calling package
    // (com.android.shell) matches the caller uid (2000) — the system-context
    // resolver attributes calls to package "android" and AppOps rejects it.
    private class ShellResolver(context: Context) : ContentResolver(context)

    /** Returns 0 on success. */
    fun runPoc(): Int {
        val resolver = ShellResolver(FakeContext.get())
        val bundle = try {
            resolver.call(AUTHORITY, "getBinder", null, null)
        } catch (e: Exception) {
            System.err.println("[AaPoc] provider call threw: ${e.message}")
            return 2
        } ?: run {
            System.err.println("[AaPoc] provider returned null bundle")
            return 3
        }

        val binder = bundle.getBinder("binder") ?: run {
            System.err.println("[AaPoc] bundle has no binder")
            return 4
        }
        val bridge = IAaBridge.Stub.asInterface(binder) ?: run {
            System.err.println("[AaPoc] asInterface failed")
            return 5
        }

        return try {
            val reply = bridge.ping("poc")
            System.err.println("[AaPoc] ping reply: $reply")
            if (reply == "pong:poc") 0 else 6
        } catch (e: Exception) {
            System.err.println("[AaPoc] ping failed: ${e.message}")
            7
        }
    }

    /**
     * Validates ServiceManager.addService from app_process — the alternative
     * bridge transport with the daemon as binder SERVER (app fetches it via
     * ServiceManager.getService; no AMS process record needed on either side).
     */
    fun runSmPoc(): Int {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val add = sm.getMethod("addService", String::class.java, android.os.IBinder::class.java)
            add.invoke(null, "dilink.auto.test", android.os.Binder())
            System.err.println("[AaSmPoc] addService OK — holding 15s")
            Thread.sleep(15_000)
            System.err.println("[AaSmPoc] done")
            0
        } catch (e: java.lang.reflect.InvocationTargetException) {
            System.err.println("[AaSmPoc] addService failed: ${e.targetException?.message}")
            1
        } catch (e: Exception) {
            System.err.println("[AaSmPoc] reflection failed: ${e.message}")
            2
        }
    }
}
