package com.dilinkauto.client.auto

import android.content.Context
import android.view.Surface
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.display.VirtualDisplayClient
import com.dilinkauto.client.service.DaemonDeployer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates an Android Auto mirror session: binds the lifecycle (:19647)
 * and video/input (:9638/:9639) listeners, starts dilinkd connecting back to
 * localhost, and pipes the decoded stream into the AA host surface.
 */
class AaMirrorController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var vdClient: VirtualDisplayClient? = null
    private var videoClient: AaVideoClient? = null
    private var inputClient: AaInputClient? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    fun start(width: Int, height: Int, dpi: Int, surface: Surface) {
        surfaceW = width
        surfaceH = height
        val vd = VirtualDisplayClient(scope, context.applicationContext).also { vdClient = it }
        val video = AaVideoClient(surface, width, height).also { videoClient = it }
        val input = AaInputClient().also { inputClient = it }

        // Bind every listener BEFORE starting the daemon: the lifecycle connect
        // is single-shot; video/input retry for 30s.
        vd.startListening()
        video.bind()
        input.bind()
        video.start()
        input.start()

        vd.onDisplayReady = {
            FileLog.i(TAG, "AA mirror ready: displayId=${vd.displayId} ${width}x${height}@${dpi}dpi")
        }

        scope.launch(Dispatchers.IO) {
            DaemonDeployer.start(
                context.applicationContext,
                width, height, dpi,
                width, height, FPS, "127.0.0.1"
            )
        }
        scope.launch(Dispatchers.IO) {
            if (!vd.acceptConnection()) {
                FileLog.w(TAG, "AA: daemon lifecycle connect failed")
            }
        }
    }

    fun tap(x: Float, y: Float) {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        inputClient?.tap(x / w, y / h)
    }

    fun stop() {
        // CMD_STOP first so the daemon restores display power; pkill as backstop.
        vdClient?.stopVdServer()
        videoClient?.stop()
        inputClient?.stop()
        vdClient?.disconnect()
        scope.launch(Dispatchers.IO) {
            delay(500)
            DaemonDeployer.stop()
        }
        vdClient = null
        videoClient = null
        inputClient = null
    }

    private companion object {
        private const val TAG = "AaMirrorController"
        private const val FPS = 30
    }
}
