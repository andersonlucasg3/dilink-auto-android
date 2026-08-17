package com.dilinkauto.client.launcher

import android.graphics.SurfaceTexture
import android.view.Surface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dilinkauto.client.auto.AaUiState
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.auto.AaDaemonClient
import com.dilinkauto.client.auto.AaInput
import com.dilinkauto.client.launcher.nav.PersistentNavBar
import com.dilinkauto.client.launcher.nav.RecentAppsState
import com.dilinkauto.client.launcher.screen.AppGrid
import com.dilinkauto.client.launcher.screen.NotificationContent
import com.dilinkauto.client.launcher.theme.CarTheme
import com.dilinkauto.client.service.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Home screen of the AA virtual display (VD1). The daemon starts it by explicit
 * component on the VD — the stock launcher is singleTask on display 0, so a
 * generic HOME intent would background the host app.
 *
 * Dual-VD architecture:
 * - VD1 (this launcher): app grid + nav bar. Apps launch on VD2.
 * - VD2 (secondary): renders the active app via TextureView. Touch events are
 *   forwarded through AaInput (root injector).
 *
 * Layout: nav bar on the RIGHT, content (grid or VD2 viewport) on the LEFT.
 */
class DiLinkLauncher : ComponentActivity() {

    private var screen by mutableStateOf(Screen.HOME)

    /** Package name of the app currently shown on VD2, or null if on home grid. */
    var activeApp by mutableStateOf<String?>(null)
        private set

    /** App waiting for VD2 creation before we can launch it. */
    private var pendingApp: String? = null

    // ── VD2 state ──

    @Volatile private var vd2DisplayId = -1

    private val handler = Handler(Looper.getMainLooper())
    private var backResetRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // VD2 ready → launch any app that was waiting for it
        AaDaemonClient.onSecondaryDisplayReady = { id ->
            FileLog.i(TAG, "VD2 ready: id=$id")
            vd2DisplayId = id
            AaUiState.vd2DisplayId.value = id
            val pkg = pendingApp
            if (pkg != null) {
                pendingApp = null
                launchOnVd2(id, pkg)
            }
        }

        // VD2 stack emptied → reset launcher state back to home grid
        AaDaemonClient.onDisplayStackEmpty = { id ->
            FileLog.i(TAG, "VD stack empty: id=$id (current vd2=$vd2DisplayId)")
            if (id == vd2DisplayId) {
                backResetRunnable?.let { handler.removeCallbacks(it) }
                backResetRunnable = null
                activeApp = null
                vd2DisplayId = -1
                screen = Screen.HOME
                AaUiState.activeApp.value = null
                AaUiState.vd2DisplayId.value = -1
            }
        }

        // MirrorScreen navbar actions → keep VD1 launcher UI in sync
        AaUiState.onNavAction = { action ->
            when (action) {
                "home" -> handleHome()
                "back" -> handleBack()
                "notifications" -> {
                    screen = if (screen == Screen.NOTIFICATIONS) Screen.HOME
                    else Screen.NOTIFICATIONS
                }
                else -> {
                    if (action.startsWith("recent:")) {
                        val pkg = action.removePrefix("recent:")
                        launchApp(pkg)
                    }
                }
            }
        }

        setContent {
            CarTheme {
                LauncherHome(
                    screen = screen,
                    activeApp = activeApp,
                    onScreenChange = { screen = it },
                    onLaunchApp = { pkg -> launchApp(pkg) },
                    onBack = { handleBack() },
                    onHome = { handleHome() }
                )
            }
        }

        // Init recents early (same process)
        RecentAppsState.init(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // Don't reset to HOME while an app is active on VD2
        if (activeApp == null) screen = Screen.HOME
    }

    /**
     * User tapped an app in the grid (or a recent in the nav bar).
     * Set activeApp → TextureView appears → VD2 created → app launched.
     * If VD2 already exists, launch the app directly.
     */
    private fun launchApp(packageName: String) {
        activeApp = packageName
        pendingApp = packageName
        screen = Screen.HOME // hide notifications if open
        AaUiState.activeApp.value = packageName

        val id = vd2DisplayId
        if (id >= 0) {
            pendingApp = null
            launchOnVd2(id, packageName)
        }
        // else: TextureView will trigger createSecondaryDisplay →
        //       onSecondaryDisplayReady → pendingApp launched
    }

    private fun launchOnVd2(displayId: Int, packageName: String) {
        FileLog.i(TAG, "launchOnVd2 id=$displayId pkg=$packageName")
        try {
            AaDaemonClient.daemon?.launchAppOnDisplay(displayId, packageName)
        } catch (e: Exception) {
            FileLog.w(TAG, "launchAppOnDisplay failed: ${e.message}")
        }
    }

    private fun handleBack() {
        val id = vd2DisplayId
        if (activeApp != null && id >= 0) {
            // Primary: root injector via AaInput.keyOn — uses IInputManager
            // directly (binder), so it works even without window focus
            // (mCurrentFocus=null on freshly-opened VD2). The daemon's
            // goBackOnDisplay relies on `input -d keyevent` which needs focus.
            var injected = false
            try {
                injected = AaInput.keyOn(id, KeyEvent.KEYCODE_BACK)
            } catch (_: Exception) {}
            if (!injected) {
                // Fallback: daemon injection (may fail without focus on HyperOS
                // but still schedules the stack-empty watcher)
                try { AaDaemonClient.daemon?.goBackOnDisplay(id) } catch (_: Exception) {}
            }
            // Fallback: if daemon doesn't notify stack-empty in 2.5s, reset anyway
            backResetRunnable?.let { handler.removeCallbacks(it) }
            backResetRunnable = Runnable {
                if (activeApp != null) {
                    FileLog.w(TAG, "VD2 back fallback: resetting without daemon notify")
                    activeApp = null
                    vd2DisplayId = -1
                    screen = Screen.HOME
                    AaUiState.activeApp.value = null
                    AaUiState.vd2DisplayId.value = -1
                }
            }
            handler.postDelayed(backResetRunnable!!, 2500)
        } else {
            // Bug #1 fix: on home screen (VD1), inject BACK via the root
            // injector. On HyperOS the daemon (shell) cannot inject into
            // virtual displays, so the keyboard path is the only option.
            val vd1Id = AaDaemonClient.displayId
            if (vd1Id >= 0) {
                try {
                    AaInput.keyOn(vd1Id, KeyEvent.KEYCODE_BACK)
                } catch (_: Exception) {
                    // Fallback: let the daemon try its own goBack
                    try { AaDaemonClient.daemon?.goBack() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleHome() {
        activeApp = null
        pendingApp = null
        vd2DisplayId = -1
        screen = Screen.HOME
        AaUiState.activeApp.value = null
        AaUiState.vd2DisplayId.value = -1
    }

    companion object {
        private const val TAG = "DiLinkLauncher"
    }
}

// ── Screen state ──

private enum class Screen { HOME, NOTIFICATIONS }

// ── Root composable ──

@Composable
private fun LauncherHome(
    screen: Screen,
    activeApp: String?,
    onScreenChange: (Screen) -> Unit,
    onLaunchApp: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit
) {
    val context = LocalContext.current
    val recentAppsState = remember { RecentAppsState }

    val apps by produceState(initialValue = emptyList<LauncherApp>()) {
        value = loadLaunchableApps(context)
    }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            recentAppsState.pruneUnavailable(apps.map { it.packageName }.toSet())
        }
    }

    val notifications by NotificationService.notificationsFlow.collectAsState()

    val launchAndTrack: (String) -> Unit = { pkg ->
        recentAppsState.onAppLaunched(pkg)
        onLaunchApp(pkg)
    }

    // Keep-alive: subtle alpha pulse prevents the VD encoder from stopping
    // when the UI is static (>15 s), which would kill the stream session.
    val keepAliveTransition = rememberInfiniteTransition(label = "keepAlive")
    val keepAliveAlpha by keepAliveTransition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "keepAliveAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .graphicsLayer { alpha = keepAliveAlpha }
    ) {
        // ── LEFT: content area (grid or VD2 viewport) ──
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                activeApp != null -> {
                    Vd2Surface(modifier = Modifier.fillMaxSize())
                }
                else -> {
                    when (screen) {
                        Screen.HOME -> AppGrid(
                            apps = apps,
                            onAppClick = launchAndTrack,
                            modifier = Modifier.fillMaxSize()
                        )
                        Screen.NOTIFICATIONS -> NotificationContent(
                            notifications = notifications,
                            onAppLaunch = launchAndTrack,
                            onDismiss = { n ->
                                NotificationService.clearNotification(n.packageName, n.id)
                            },
                            onClearAll = { NotificationService.clearAllNotifications() }
                        )
                    }
                }
            }
        }

        // ── RIGHT: persistent nav bar ──
        PersistentNavBar(
            recentAppsState = recentAppsState,
            appList = apps,
            notificationCount = notifications.size,
            onAppClick = { pkg -> onLaunchApp(pkg) },
            onBack = onBack,
            onHome = onHome,
            onNotifications = { onScreenChange(Screen.NOTIFICATIONS) }
        )
    }
}

// ── VD2 surface (TextureView rendering the secondary display) ──

/**
 * Renders VD2 inside a TextureView. Creates the secondary VirtualDisplay when
 * the surface is available. Touch events are NOT handled here — MirrorScreen
 * does the hit-test and injects directly into VD1 or VD2 via AaInput.
 */
@Composable
private fun Vd2Surface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = context.resources.displayMetrics.densityDpi

    // Mutable state shared with the TextureView factory closures
    var vd2Id by remember { mutableStateOf(-1) }
    var vd2W by remember { mutableStateOf(0) }
    var vd2H by remember { mutableStateOf(0) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        st: SurfaceTexture, width: Int, height: Int
                    ) {
                        FileLog.i("Vd2Surface", "SurfaceTexture available: ${width}x${height}@${dpi}dpi")
                        vd2W = width
                        vd2H = height
                        val surface = Surface(st)
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val id = AaDaemonClient.daemon?.createSecondaryDisplay(
                                    surface, width, height, dpi
                                )
                                if (id != null && id >= 0) {
                                    vd2Id = id
                                    AaUiState.vd2DisplayId.value = id
                                    FileLog.i("Vd2Surface", "VD2 created: id=$id")
                                }
                            } catch (e: Exception) {
                                FileLog.w("Vd2Surface", "createSecondaryDisplay failed: ${e.message}")
                            }
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        st: SurfaceTexture, width: Int, height: Int
                    ) {
                        vd2W = width
                        vd2H = height
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        FileLog.i("Vd2Surface", "SurfaceTexture destroyed — releasing VD2")
                        val id = vd2Id
                        if (id >= 0) {
                            try {
                                AaDaemonClient.daemon?.releaseSecondaryDisplay(id)
                            } catch (_: Exception) {}
                        }
                        vd2Id = -1
                        AaUiState.vd2DisplayId.value = -1
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier
    )
}

