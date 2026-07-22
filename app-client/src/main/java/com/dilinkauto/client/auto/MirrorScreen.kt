package com.dilinkauto.client.auto

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.service.DaemonDeployer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android Auto mirror screen: a map-only NavigationTemplate whose surface IS
 * the virtual display — the shell daemon creates the VD directly on it
 * (no streaming, no codec). Taps go back over the binder bridge.
 */
class MirrorScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_back))
                    .setOnClickListener {
                        try { AaDaemonClient.daemon?.goBack() } catch (_: Exception) {}
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_exit))
                    .setOnClickListener { finish() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        surfaceW = width
        surfaceH = height
        FileLog.i(TAG, "AA surface available: ${width}x${height}@${dpi}dpi")
        lifecycleScope.launch(Dispatchers.IO) {
            if (!DaemonDeployer.startAaDaemon(carContext.applicationContext)) {
                FileLog.w(TAG, "AA: no privileged backend to start daemon")
                return@launch
            }
            val daemon = AaDaemonClient.awaitDaemon()
            if (daemon == null) {
                FileLog.w(TAG, "AA: daemon did not publish bridge in time")
                return@launch
            }
            daemon.setSurface(surface, width, height, dpi)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        FileLog.i(TAG, "AA surface destroyed")
        try { AaDaemonClient.daemon?.surfaceDestroyed() } catch (_: Exception) {}
        AaDaemonClient.reset()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Letterboxing/insets ignored for now — surface matches VD dims
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Same as above
    }

    override fun onClick(x: Float, y: Float) {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        val xn = x / w
        val yn = y / h
        try {
            AaDaemonClient.daemon?.touch(ACTION_DOWN, xn, yn)
            AaDaemonClient.daemon?.touch(ACTION_UP, xn, yn)
        } catch (_: Exception) {}
    }

    private companion object {
        private const val TAG = "MirrorScreen"
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 2
    }
}
