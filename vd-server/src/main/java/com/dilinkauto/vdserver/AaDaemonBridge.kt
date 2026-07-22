package com.dilinkauto.vdserver

import android.view.Surface
import com.dilinkauto.protocol.aidl.IAaAppCallback
import com.dilinkauto.protocol.aidl.IAaDaemon
import kotlin.system.exitProcess

/**
 * IAaDaemon implementation served by the shell daemon. Published on
 * ServiceManager; the phone app drives the virtual display through it.
 */
class AaDaemonBridge : IAaDaemon.Stub() {

    companion object {
        const val SERVICE_NAME = "dilink.auto.daemon"
    }

    private val nb = NativeBridge()

    @Volatile private var displayId = -1
    @Volatile private var vdWidth = 0
    @Volatile private var vdHeight = 0
    @Volatile private var appCallback: IAaAppCallback? = null

    fun publish(): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("addService", String::class.java, android.os.IBinder::class.java)
                .invoke(null, SERVICE_NAME, this)
            true
        } catch (e: Exception) {
            System.err.println("[AaDaemon] addService failed: ${e.cause?.message ?: e.message}")
            false
        }
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
        nb.execShell("am start --display $id -a android.intent.action.MAIN -c android.intent.category.HOME")
        System.err.println("[AaDaemon] VD ready: id=$id")
        appCallback?.onDisplayReady(id)
    }

    override fun surfaceDestroyed() {
        System.err.println("[AaDaemon] surfaceDestroyed")
        displayId = -1
    }

    override fun touch(action: Int, xNorm: Float, yNorm: Float) {
        val id = displayId
        if (id < 0 || vdWidth <= 0 || vdHeight <= 0) return
        val x = (xNorm.coerceIn(0f, 1f) * vdWidth).toInt()
        val y = (yNorm.coerceIn(0f, 1f) * vdHeight).toInt()
        nb.injectMotionEvent(id, action, "0,0,$x,$y,1.0")
    }

    override fun goBack() {
        val id = displayId
        if (id < 0) return
        nb.execShell("input -d $id keyevent 4")
    }

    override fun goHome() {
        val id = displayId
        if (id < 0) return
        nb.execShell("am start --display $id -a android.intent.action.MAIN -c android.intent.category.HOME")
    }

    override fun launchApp(packageName: String) {
        val id = displayId
        if (id < 0) return
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
