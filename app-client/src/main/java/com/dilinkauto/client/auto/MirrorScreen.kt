package com.dilinkauto.client.auto

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.RootManager
import com.dilinkauto.client.launcher.navrail.NavRailService
import com.dilinkauto.client.service.DaemonDeployer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Auto mirror screen: a map-only NavigationTemplate whose surface IS
 * the virtual display — the shell daemon creates the VD directly on it
 * (no streaming, no codec). Taps go back over the binder bridge.
 */
class MirrorScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

    /** Full surface dims as reported by the host (never shrunk). */
    @Volatile private var fullW = 0
    @Volatile private var fullH = 0
    /** Current VD dims — stable area when the host reports one, else full. */
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var surfaceDpi = 0
    @Volatile private var currentSurface: android.view.Surface? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * During the AA session: hold a PARTIAL wakelock (CPU alive), disable the
     * keyguard and power the physical panel OFF — projection doesn't need it
     * (VD is virtual). Wake sources (notifications, charger) can re-power the
     * panel, so a loop re-enforces power-off every 30s while the session is
     * live. Everything is restored on session end.
     */
    private fun keepAwake(on: Boolean) {
        if (on) {
            if (wakeLock != null) return
            val pm = carContext.getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "DiLink:aaSession"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            FileLog.i(TAG, "AA session: wakelock + lockscreen off + panel off")
            panelOffThread = kotlin.concurrent.thread(name = "AaPanelOff") {
                RootManager.execAndWait("settings put secure lockscreen_disabled 1")
                while (wakeLock != null) {
                    RootManager.execAndWait("cmd display power-off 0")
                    try { Thread.sleep(PANEL_REOFF_INTERVAL_MS) } catch (_: InterruptedException) { break }
                }
            }
        } else {
            panelOffThread?.interrupt()
            panelOffThread = null
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            FileLog.i(TAG, "AA session end: wakelock released, lockscreen + panel restored")
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                RootManager.execAndWait("cmd display power-on 0")
                RootManager.execAndWait("settings put secure lockscreen_disabled 0")
            }
        }
    }

    @Volatile private var panelOffThread: Thread? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        // Daemon restarted (or re-announced) — re-push the AA surface
        AaDaemonClient.onDaemonConnected = { pushSurface() }
        // VD up — show the persistent nav rail over fullscreen apps
        AaDaemonClient.onDisplayReady = {
            NavRailService.start(carContext.applicationContext)
        }
    }

    /** Last successful push key — skips duplicate setSurface (each one
     *  destroys the VD and migrates its tasks to the phone display). */
    @Volatile private var lastPushBinder: android.os.IBinder? = null
    @Volatile private var lastPushSurface: android.view.Surface? = null
    @Volatile private var lastPushW = 0
    @Volatile private var lastPushH = 0

    private fun pushSurface() {
        val s = currentSurface ?: return
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val daemon = AaDaemonClient.daemon ?: return@launch
            if (lastPushBinder === daemon.asBinder() && lastPushSurface === s &&
                lastPushW == w && lastPushH == h) {
                return@launch
            }
            try {
                daemon.setSurface(s, w, h, surfaceDpi)
                lastPushBinder = daemon.asBinder()
                lastPushSurface = s
                lastPushW = w
                lastPushH = h
            } catch (e: Exception) {
                lastPushBinder = null
                FileLog.w(TAG, "AA: re-push failed (${e.javaClass.simpleName})")
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Single mandatory strip action: menu icon toggles the nav rail
        // (recents / Home / Back). Host-rendered, always visible — the most
        // reliable rail trigger we have (edge-swipe is undetectable via
        // SurfaceCallback's position-less scroll deltas).
        val menuIcon = CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(
                carContext, R.drawable.ic_menu)
        ).build()
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(menuIcon)
                    .setOnClickListener {
                        NavRailService.toggle(carContext.applicationContext)
                    }
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
        fullW = width
        fullH = height
        surfaceW = width
        surfaceH = height
        surfaceDpi = dpi
        currentSurface = surface
        FileLog.i(TAG, "AA surface available: ${width}x${height}@${dpi}dpi")
        AaInput.setSurfaceSize(width, height)
        keepAwake(true)
        lifecycleScope.launch(Dispatchers.IO) {
            // Root/Shizuku probe is async — wait for the verdict before
            // concluding there is no backend (probe takes ~100ms after start).
            withTimeoutOrNull(BACKEND_PROBE_TIMEOUT_MS) {
                RootManager.isAvailableFlow.filterNotNull().first()
            }
            if (!DaemonDeployer.startAaDaemon(carContext.applicationContext)) {
                FileLog.w(TAG, "AA: no privileged backend to start daemon")
                return@launch
            }
            // Single push path: onDaemonConnected (announce) and this both go
            // through pushSurface(), which dedupes by binder+surface+dims.
            // Never call daemon.setSurface directly — every call recreates
            // the VD and migrates its tasks to the phone display.
            AaDaemonClient.awaitDaemon()
            pushSurface()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        FileLog.i(TAG, "AA surface destroyed")
        currentSurface = null
        lastPushBinder = null
        lastPushSurface = null
        lastPushW = 0
        lastPushH = 0
        keepAwake(false)
        NavRailService.stop(carContext.applicationContext)
        try { AaDaemonClient.daemon?.surfaceDestroyed() } catch (_: Exception) {}
        AaDaemonClient.reset()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        FileLog.i(TAG, "AA visible area: $visibleArea")
    }

    /**
     * The host reports the rectangle NOT occluded by its chrome (action
     * strip). Size the VD to it so app content never renders under the
     * strip button. A VD always draws at (0,0) of the surface, so an
     * offset origin can't be honored — log and keep the full surface.
     */
    override fun onStableAreaChanged(stableArea: Rect) {
        FileLog.i(TAG, "AA stable area: $stableArea (surface ${fullW}x${fullH})")
        AaUiState.stableArea.value = stableArea
        // VD layout rule (user): content ends before the host's Exit button
        // area — right inset = 4x the host-reported chrome inset, height is
        // always 100% of the surface.
        val wantW = if (!stableArea.isEmpty && stableArea.right > 0) {
            val chromeInset = (fullW - stableArea.right).coerceAtLeast(0)
            fullW - chromeInset * 4
        } else {
            fullW
        }
        val wantH = fullH
        if (wantW <= 0 || wantH <= 0 || (wantW == surfaceW && wantH == surfaceH)) return
        // Only resize BEFORE the first successful push. Recreating the VD
        // mid-session migrates its tasks to the phone display (chrome
        // animations report changing stable areas all the time).
        if (lastPushBinder != null) {
            FileLog.i(TAG, "AA stable area changed after VD creation — ignored")
            return
        }
        surfaceW = wantW
        surfaceH = wantH
        pushSurface()
    }

    override fun onClick(x: Float, y: Float) {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        FileLog.i(TAG, "AA click x=$x y=$y")
        lifecycleScope.launch(Dispatchers.IO) {
            if (AaInput.available) {
                // HyperOS: daemon (shell) can't inject into the VD — root from app
                AaInput.tap(x.toInt(), y.toInt())
            } else {
                val xn = x / w
                val yn = y / h
                try {
                    AaDaemonClient.daemon?.touch(ACTION_DOWN, xn, yn)
                    AaDaemonClient.daemon?.touch(ACTION_UP, xn, yn)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // Root injector only — the daemon (shell) has no scroll equivalent yet
        if (!AaInput.available) return
        lifecycleScope.launch(Dispatchers.IO) {
            AaInput.scrollBy(distanceX, distanceY)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!AaInput.available) return
        lifecycleScope.launch(Dispatchers.IO) {
            AaInput.fling(velocityX, velocityY)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!AaInput.available) return
        lifecycleScope.launch(Dispatchers.IO) {
            AaInput.scale(focusX, focusY, scaleFactor)
        }
    }

    private companion object {
        private const val TAG = "MirrorScreen"
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 2
        private const val BACKEND_PROBE_TIMEOUT_MS = 10_000L
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val PANEL_REOFF_INTERVAL_MS = 30_000L
    }
}
