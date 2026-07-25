package com.dilinkauto.vdserver

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.view.Surface
import com.dilinkauto.protocol.AaBridge
import com.dilinkauto.protocol.aidl.IAaAppCallback
import com.dilinkauto.protocol.aidl.IAaDaemon
import java.util.UUID
import kotlin.system.exitProcess

/**
 * IAaDaemon implementation served by the shell daemon. The binder reaches the
 * app inside an explicit broadcast Intent — custom ServiceManager services are
 * invisible to untrusted_app (AOSP sepolicy denies { find }), so the daemon
 * announces itself instead of registering a service.
 */
class AaDaemonBridge : IAaDaemon.Stub() {

    private val nb = NativeBridge()

    @Volatile private var displayId = -1
    @Volatile private var vdWidth = 0
    @Volatile private var vdHeight = 0
    @Volatile private var appCallback: IAaAppCallback? = null
    private val reannouncePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Broadcast the daemon binder to the app until it registers its callback
     * (or attempts run out). The explicit broadcast starts the app process if
     * needed (FLAG_INCLUDE_STOPPED_PACKAGES).
     */
    fun announce(maxAttempts: Int = 30): Boolean {
        val am = try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
            Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: Exception) {
            System.err.println("[AaDaemon] IActivityManager unavailable: ${e.message}")
            return false
        }

        // Anti-spoof token: the app cross-checks it against a shell-readable file
        val token = UUID.randomUUID().toString()
        nb.execShell("echo $token > ${AaBridge.TOKEN_FILE} && chmod 600 ${AaBridge.TOKEN_FILE}")

        val intent = Intent(AaBridge.ACTION_ANNOUNCE).apply {
            component = ComponentName(AaBridge.APP_PACKAGE, AaBridge.RECEIVER_FQCN)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            // putExtra(String, IBinder) is @hide in the SDK — call via reflection
            Intent::class.java.getMethod("putExtra", String::class.java, IBinder::class.java)
                .invoke(this, AaBridge.EXTRA_BINDER, this@AaDaemonBridge)
            putExtra(AaBridge.EXTRA_TOKEN, token)
        }

        // Signature drifts across releases — resolve by name and map args by type
        val broadcast = try {
            am.javaClass.methods.first { it.name == "broadcastIntent" }
        } catch (e: Exception) {
            System.err.println("[AaDaemon] broadcastIntent method not found: ${e.message}")
            return false
        }
        val args = arrayOfNulls<Any?>(broadcast.parameterCount)
        args[0] = null // IApplicationThread
        args[1] = intent
        for (i in 2 until broadcast.parameterCount) {
            args[i] = when (broadcast.parameterTypes[i]) {
                // int followed by Bundle is appOp — must be OP_NONE (-1); 0 is
                // OP_COARSE_LOCATION, which Android 15+ enforces on delivery
                Int::class.javaPrimitiveType ->
                    if (broadcast.parameterTypes.getOrNull(i + 1) == android.os.Bundle::class.java) -1 else 0
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        args[broadcast.parameterCount - 1] = -2 // userId = USER_CURRENT

        repeat(maxAttempts) { attempt ->
            try {
                broadcast.invoke(am, *args)
                System.err.println("[AaDaemon] announce sent (attempt ${attempt + 1})")
            } catch (e: Exception) {
                System.err.println("[AaDaemon] broadcast failed: ${e.cause?.message ?: e.message}")
            }
            if (appCallback != null) {
                System.err.println("[AaDaemon] app callback registered — bridge up")
                return true
            }
            Thread.sleep(1000)
        }
        System.err.println("[AaDaemon] app never registered after $maxAttempts attempts")
        return false
    }

    override fun setSurface(surface: Surface, width: Int, height: Int, dpi: Int) {
        System.err.println("[AaDaemon] setSurface ${width}x${height}@${dpi}dpi valid=${surface.isValid}")
        val id = nb.createVirtualDisplay(width, height, dpi, surface)
        if (id < 0) {
            appCallback?.onError("VD creation failed")
            return
        }
        displayId = id
        vdWidth = width
        vdHeight = height
        // Force landscape inside the VD: portrait-only apps can't rotate it
        nb.execShell("wm set-ignore-orientation-request -d $id true")
        nb.execShell("am start --display $id -n ${AaBridge.APP_PACKAGE}/${AaBridge.LAUNCHER_FQCN}")
        System.err.println("[AaDaemon] VD ready: id=$id")
        appCallback?.onDisplayReady(id)
    }

    override fun surfaceDestroyed() {
        System.err.println("[AaDaemon] surfaceDestroyed")
        displayId = -1
        nb.releaseVirtualDisplay()
        // The app resets its client when the surface goes away — re-announce so
        // a recreated surface (rotation, host reconnect) can re-attach
        if (reannouncePending.compareAndSet(false, true)) {
            Thread({
                try {
                    Thread.sleep(500)
                    appCallback = null
                    announce()
                } catch (_: Exception) {}
                reannouncePending.set(false)
            }, "AaReannounce").start()
        }
    }

    override fun touch(action: Int, xNorm: Float, yNorm: Float) {
        val id = displayId
        if (id < 0 || vdWidth <= 0 || vdHeight <= 0) return
        val x = (xNorm.coerceIn(0f, 1f) * vdWidth).toInt()
        val y = (yNorm.coerceIn(0f, 1f) * vdHeight).toInt()
        System.err.println("[AaDaemon] touch action=$action x=$x y=$y display=$id")
        val ok = nb.injectMotionEvent(id, action, "0,0,$x,$y,1.0")
        if (!ok && action == 0) {
            // HyperOS: shell injection blocked — root tap covers DOWN+UP at once
            nb.injectTapViaRoot(id, x, y)
        }
        // An in-app back/gesture can empty the VD stack without goBack —
        // re-check after the interaction settles (debounced)
        if (action == 2) scheduleStackCheck(1500)
    }

    override fun goBack() {
        val id = displayId
        if (id < 0) return
        System.err.println("[AaDaemon] goBack on display $id")
        if (nb.shellInjectionBlocked) {
            nb.injectKeyViaRoot(id, 4)
        } else {
            nb.execShell("input -d $id keyevent 4")
        }
        scheduleStackCheck(300)
    }

    // Debounced empty-stack watcher: any path that can empty the VD back-stack
    // (strip back, in-app back via touch) schedules a check. Only the latest
    // request runs; activity teardown takes ~1s, so it polls briefly.
    @Volatile private var stackCheckSeq = 0

    private fun scheduleStackCheck(delayMs: Long) {
        val seq = ++stackCheckSeq
        Thread({
            try {
                Thread.sleep(delayMs)
                repeat(6) {
                    val id = displayId
                    if (id < 0 || seq != stackCheckSeq) return@Thread
                    if (nb.isDisplayStackEmpty(id)) {
                        System.err.println("[AaDaemon] VD stack empty — relaunching launcher")
                        nb.execShell("am start --display $id -n ${AaBridge.APP_PACKAGE}/${AaBridge.LAUNCHER_FQCN}")
                        return@Thread
                    }
                    Thread.sleep(300)
                }
            } catch (_: Exception) {}
        }, "AaStackCheck").start()
    }

    override fun goHome() {
        val id = displayId
        if (id < 0) return
        nb.execShell("am start --display $id -n ${AaBridge.APP_PACKAGE}/${AaBridge.LAUNCHER_FQCN}")
    }

    override fun launchApp(packageName: String) {
        val id = displayId
        if (id < 0) return
        System.err.println("[AaDaemon] launchApp $packageName on display $id")
        nb.launchApp(id, packageName)
    }

    override fun registerAppCallback(cb: IAaAppCallback?) {
        appCallback = cb
        cb?.asBinder()?.let { binder ->
            try {
                binder.linkToDeath({
                    System.err.println("[AaDaemon] app died — shutting down")
                    exitProcess(0)
                }, 0)
            } catch (_: Exception) {}
        }
    }

    override fun shutdown() {
        exitProcess(0)
    }
}
