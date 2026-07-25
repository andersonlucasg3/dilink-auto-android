package com.dilinkauto.client.debug

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.auto.AaDaemonClient
import com.dilinkauto.client.service.DaemonDeployer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug-only bridge harness: a SurfaceView that becomes the VD host.
 * Validates the full chain — daemon publish, getService, setSurface,
 * VD rendering on an external surface, and MotionEvent injection —
 * without the Android Auto host (DHU or car).
 */
class BridgeTestActivity : Activity(), SurfaceHolder.Callback, View.OnTouchListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var currentSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@BridgeTestActivity)
            setOnTouchListener(this@BridgeTestActivity)
        }
        statusView = TextView(this).apply {
            text = "Bridge Test\nWaiting for surface..."
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 24, 24, 24)
        }
        setContentView(surfaceView)
        addContentView(statusView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AaDaemonClient.onDisplayReady = { id -> status("VD ready: displayId=$id") }
        AaDaemonClient.onError = { msg -> status("Daemon error: $msg") }
        // Daemon restarted (or re-announced after churn) — re-push the surface
        AaDaemonClient.onDaemonConnected = { pushSurface() }
    }

    private fun pushSurface() {
        val s = currentSurface ?: return
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        scope.launch(Dispatchers.IO) {
            try {
                status("Pushing surface to daemon...")
                AaDaemonClient.daemon?.setSurface(s, w, h, resources.displayMetrics.densityDpi)
            } catch (_: Exception) {}
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        surfaceW = frame.width()
        surfaceH = frame.height()
        currentSurface = holder.surface
        status("Surface ${surfaceW}x${surfaceH} — starting daemon...")
        scope.launch(Dispatchers.IO) {
            // Privileged launch works on the rooted phone; on emulator/backend-less
            // devices the daemon must be started manually via adb shell
            if (!DaemonDeployer.startAaDaemon(applicationContext)) {
                status("No backend — start the daemon manually: adb shell 'CLASSPATH=/data/local/tmp/vd-server.jar app_process / com.dilinkauto.vdserver.DaemonEntry aa-daemon &'")
            }
            val daemon = AaDaemonClient.awaitDaemon()
            if (daemon == null) {
                status("Daemon not found within timeout")
                return@launch
            }
            status("Daemon found — setSurface...")
            daemon.setSurface(holder.surface, surfaceW, surfaceH,
                resources.displayMetrics.densityDpi)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        FileLog.i(TAG, "Surface destroyed")
        currentSurface = null
        try { AaDaemonClient.daemon?.surfaceDestroyed() } catch (_: Exception) {}
        AaDaemonClient.reset()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendTouch(0, event.x / w, event.y / h)
            MotionEvent.ACTION_MOVE -> sendTouch(1, event.x / w, event.y / h)
            MotionEvent.ACTION_UP -> sendTouch(2, event.x / w, event.y / h)
        }
        return true
    }

    private fun sendTouch(action: Int, xn: Float, yn: Float) {
        try { AaDaemonClient.daemon?.touch(action, xn, yn) } catch (_: Exception) {}
    }

    // Forward the host Back button to the VD (exercises goBack + empty-stack relaunch)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try { AaDaemonClient.daemon?.goBack() } catch (_: Exception) {}
    }

    private fun status(msg: String) {
        FileLog.i(TAG, msg)
        scope.launch(Dispatchers.Main) { statusView.text = "Bridge Test\n$msg" }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "BridgeTest"
    }
}
