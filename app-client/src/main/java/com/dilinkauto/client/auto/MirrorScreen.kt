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
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.RootManager
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
     * During the AA session: hold a SCREEN_BRIGHT wakelock (keeps the display
     * logically ON so the phone never locks/suspends), disable the keyguard and
     * power the physical panel OFF — projection doesn't need it (VD is virtual).
     * Wake sources (notifications, charger) can re-power the panel, so a loop
     * re-enforces power-off every 30s while the session is live. Everything is
     * restored on session end.
     */
    private fun keepAwake(on: Boolean) {
        if (on) {
            if (wakeLock != null) return
            val pm = carContext.getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "DiLink:aaSession"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            FileLog.i(TAG, "AA session: wakelock + lockscreen off + panel off")
            panelOffThread = kotlin.concurrent.thread(name = "AaPanelOff") {
                RootManager.execAndWait("settings put secure lockscreen_disabled 1")
                while (true) {
                    val wl = wakeLock ?: break
                    // The wakelock has a 4h safety timeout — renew it every
                    // cycle so a longer AA session never lets the CPU suspend.
                    wl.acquire(WAKELOCK_TIMEOUT_MS)
                    // Raced with keepAwake(false)? Undo the stray re-acquire.
                    if (wakeLock !== wl) {
                        if (wl.isHeld) wl.release()
                        break
                    }
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
        // Daemon restarted (or re-announced) — re-push the AA surface
        AaDaemonClient.onDaemonConnected = { pushSurface() }
        // VD up — pre-spawn the root input injector (0.5–1.5s) before the first tap
        AaDaemonClient.onDisplayReady = { displayId ->
            AaInput.setTargetDisplay(displayId)
            AaInput.setSurfaceSize((surfaceW * VD_SCALE).toInt(), (surfaceH * VD_SCALE).toInt())
            kotlin.concurrent.thread { AaInput.warmUp() }
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
                // Supersample: the VD renders VD_SCALE× larger than the host
                // surface and the host encoder downscales — sharper text for
                // some extra GPU cost. Dpi scales with it so app UI keeps the
                // same visual size, just rasterized with more pixels.
                daemon.setSurface(s, (w * VD_SCALE).toInt(), (h * VD_SCALE).toInt(),
                    (surfaceDpi * VD_SCALE).toInt())
                lastPushBinder = daemon.asBinder()
                lastPushSurface = s
                lastPushW = w
                lastPushH = h
                // The injector's coordinate space is the VD, not the surface
                AaInput.setSurfaceSize((w * VD_SCALE).toInt(), (h * VD_SCALE).toInt())
            } catch (e: Exception) {
                lastPushBinder = null
                FileLog.w(TAG, "AA: re-push failed (${e.javaClass.simpleName})")
            }
        }
    }

    override fun onGetTemplate(): Template {
        FileLog.i(TAG, "onGetTemplate called — registering surface callback")
        // setSurfaceCallback here (not init) — the host is ready when it asks
        // for the template, so the callback registration actually lands.
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
            FileLog.i(TAG, "setSurfaceCallback registered OK")
        } catch (e: Exception) {
            FileLog.w(TAG, "setSurfaceCallback failed: ${e.message}")
        }
        // NavigationTemplate REQUIRES an ActionStrip — build() throws
        // IllegalStateException without one. Provide a minimal Home button
        // that finishes this screen (returns to the launcher).
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(
                                CarIcon.Builder(
                                    IconCompat.createWithResource(
                                        carContext,
                                        R.drawable.ic_home
                                    )
                                ).build()
                            )
                            .setOnClickListener { finish() }
                            .build()
                    )
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build()
            )
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
        AaUiState.onStableAreaChanged?.invoke(stableArea)
    }

    override fun onClick(x: Float, y: Float) {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        FileLog.i(TAG, "AA click x=$x y=$y")

        // ── Hit-test: navbar column ──
        // Navbar is 86dp wide at the right edge of VD1 content.
        // Account for host chrome: the stable area right edge is where
        // the chrome begins, so the navbar's right edge aligns with it.
        val stableArea = AaUiState.stableArea.value
        val navbarRight = stableArea?.right ?: w
        val navbarLeft = navbarRight - (86 * surfaceDpi / 160).toInt()

        if (x >= navbarLeft) {
            // Navbar area — find which button and call action directly
            val bounds = AaUiState.navbarBounds.value
            val button = bounds?.entries?.firstOrNull { (_, rect) ->
                rect.contains(x.toInt(), y.toInt())
            }
            FileLog.i(TAG, "navbar button=${button?.key} at ($x, $y)")
            when {
                button?.key == "home" -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { AaDaemonClient.daemon?.goHome() } catch (_: Exception) {}
                    }
                    AaUiState.onNavAction?.invoke("home")
                }
                button?.key == "back" -> {
                    // Chama daemon diretamente (como home faz) + UI sync
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val vd2Id = AaUiState.vd2DisplayId.value
                            val activeApp = AaUiState.activeApp.value
                            if (activeApp != null && vd2Id >= 0) {
                                AaDaemonClient.daemon?.goBackOnDisplay(vd2Id)
                            } else {
                                AaDaemonClient.daemon?.goBack()
                            }
                        } catch (_: Exception) {}
                    }
                    AaUiState.onNavAction?.invoke("back")
                }
                button?.key == "notifications" -> {
                    // Toggle notifications — launcher handles the UI toggle
                    AaUiState.onNavAction?.invoke("notifications")
                }
                button?.key?.startsWith("recent:") == true -> {
                    val pkg = button.key.removePrefix("recent:")
                    val vd2Id = AaUiState.vd2DisplayId.value
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            if (vd2Id >= 0) {
                                AaDaemonClient.daemon?.launchAppOnDisplay(vd2Id, pkg)
                            }
                        } catch (_: Exception) {}
                    }
                    AaUiState.onNavAction?.invoke("recent:$pkg")
                }
                else -> {
                    // Click in navbar dead space — ignored
                    FileLog.i(TAG, "navbar click unmapped at ($x, $y)")
                }
            }
            return
        }

        // ── Content area: VD2 or VD1 grid ──
        val activeApp = AaUiState.activeApp.value
        val vd2Id = AaUiState.vd2DisplayId.value

        lifecycleScope.launch(Dispatchers.IO) {
            if (AaInput.available) {
                if (activeApp != null && vd2Id >= 0) {
                    // VD2 area — inject directly (x=0 on VD2 = left edge of content)
                    AaInput.tapOn(vd2Id, x.toInt(), y.toInt())
                } else {
                    // Grid area — inject into VD1 (1:1 mapping, VD_SCALE=1.0)
                    AaInput.tapOn(AaDaemonClient.displayId, x.toInt(), y.toInt())
                }
            } else {
                // Daemon fallback: normalized coordinates on VD1
                // VD2 injection via daemon is not supported — only VD1
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
        if (!AaInput.available) return
        val activeApp = AaUiState.activeApp.value
        val vd2Id = AaUiState.vd2DisplayId.value
        lifecycleScope.launch(Dispatchers.IO) {
            if (activeApp != null && vd2Id >= 0) {
                AaInput.scrollByOn(vd2Id, distanceX, distanceY)
            } else {
                AaInput.scrollByOn(AaDaemonClient.displayId, distanceX, distanceY)
            }
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!AaInput.available) return
        val activeApp = AaUiState.activeApp.value
        val vd2Id = AaUiState.vd2DisplayId.value
        lifecycleScope.launch(Dispatchers.IO) {
            if (activeApp != null && vd2Id >= 0) {
                AaInput.flingOn(vd2Id, velocityX, velocityY)
            } else {
                AaInput.flingOn(AaDaemonClient.displayId, velocityX, velocityY)
            }
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!AaInput.available) return
        val activeApp = AaUiState.activeApp.value
        val vd2Id = AaUiState.vd2DisplayId.value
        if (activeApp == null || vd2Id < 0) return // grid doesn't need pinch
        lifecycleScope.launch(Dispatchers.IO) {
            AaInput.scaleOn(vd2Id, focusX, focusY, scaleFactor)
        }
    }

    private companion object {
        private const val TAG = "MirrorScreen"
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 2
        private const val BACKEND_PROBE_TIMEOUT_MS = 10_000L
        // VD supersampling DISABLED: the host does NOT downscale an oversized
        // VD — the VirtualDisplay's buffer size is its own, and the host
        // surface just crops the excess (user saw zoomed/clipped content).
        // Kept as a knob; 1.0 = VD matches the host surface exactly.
        private const val VD_SCALE = 1.0f
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val PANEL_REOFF_INTERVAL_MS = 30_000L
    }
}
