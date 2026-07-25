package com.dilinkauto.client.launcher.navrail

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.RootManager
import com.dilinkauto.client.auto.AaDaemonClient
import com.dilinkauto.client.auto.AaInput
import com.dilinkauto.client.launcher.nav.RecentAppsState
import kotlin.concurrent.thread

/**
 * Swipe-in nav rail on the AA virtual display. Hidden by default as a thin
 * handle on the left edge; swipe right to expand (recent apps, Home, Back),
 * swipe left or 5s idle to collapse. Floats over fullscreen apps.
 *
 * The alpha pulse runs in BOTH states — it is the keep-alive that prevents
 * the gearhead encoder from throttling a static VD down to zero frames.
 *
 * Requires SYSTEM_ALERT_WINDOW (granted via root appops at start).
 */
class NavRailService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var container: LinearLayout? = null
    private var recentsColumn: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var density = 1f
    private var expanded = false

    private val iconCache = HashMap<String, android.graphics.Bitmap>()
    private lateinit var recentAppsState: RecentAppsState

    private val pulse = object : Runnable {
        var on = false
        override fun run() {
            on = !on
            container?.alpha = if (on) 1f else 0.97f
            handler.postDelayed(this, PULSE_INTERVAL_MS)
        }
    }

    private val refreshRecents = object : Runnable {
        override fun run() {
            if (expanded) {
                updateRecents()
                updateCurrentApp()
            }
            handler.postDelayed(this, 2000)
        }
    }

    /** Icon of the app currently on top of the VD (null/own → hidden). */
    private fun updateCurrentApp() {
        thread {
            val id = AaDaemonClient.displayId
            if (id < 0) return@thread
            val out = RootManager.execAndWait(
                "dumpsys activity activities | grep -A6 \"Display #$id \" | grep -m1 \"topResumedActivity\"") ?: return@thread
            val pkg = Regex("u0 (\\S+)/").find(out)?.groupValues?.get(1) ?: return@thread
            handler.post {
                val view = currentAppView ?: return@post
                if (pkg.contains("dilinkauto")) {
                    view.visibility = View.GONE
                    return@post
                }
                val bmp = iconCache[pkg] ?: try {
                    (packageManager.getApplicationIcon(pkg) as? BitmapDrawable)?.bitmap
                        ?.also { iconCache[pkg] = it }
                } catch (_: Exception) { null }
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                    view.visibility = View.VISIBLE
                    view.setOnClickListener {
                        thread { try { AaDaemonClient.daemon?.launchApp(pkg) } catch (_: Exception) {} }
                        scheduleAutoHide()
                    }
                }
            }
        }
    }

    private val autoHide = Runnable { collapse() }

    override fun onCreate() {
        super.onCreate()
        thread { RootManager.execAndWait("appops set $packageName SYSTEM_ALERT_WINDOW allow") }

        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.displays.firstOrNull { it.name?.contains("DiLinkAutoVD") == true }
        if (display == null) {
            FileLog.w(TAG, "VD display not found — rail not shown")
            stopSelf()
            return
        }
        val displayContext = createDisplayContext(display)
        density = displayContext.resources.displayMetrics.density
        recentAppsState = RecentAppsState(applicationContext)

        val view = buildRail(displayContext)
        val p = WindowManager.LayoutParams(
            dp(HANDLE_DP),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm = displayContext.getSystemService(WindowManager::class.java)
        try {
            wm?.addView(view, p)
            container = view
            params = p
            handler.post(pulse)
            handler.post(refreshRecents)
            FileLog.i(TAG, "nav rail (handle) shown on display ${display.displayId}")
        } catch (e: Exception) {
            FileLog.e(TAG, "addView failed: ${e.message}", e)
            stopSelf()
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    @Suppress("ClickableViewAccessibility")
    private fun buildRail(context: Context): LinearLayout {
        // Tap toggles the rail. Edge-SWIPE is not detectable: SurfaceCallback
        // delivers scrolls as position-less deltas, and the injected drag
        // anchors at the last known position — never on the handle. Taps
        // carry exact coordinates, so they always land here.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HANDLE_BG)
            setOnClickListener { if (expanded) collapse() else expand() }
        }

        // Current-app icon on top, recents below, buttons at the bottom
        currentAppView = ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { scheduleAutoHide() }
        }
        column.addView(currentAppView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))

        recentsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(recentsColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val home = railButton("⌂") { goHome() }
        column.addView(home, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))

        column.addView(View(context), LinearLayout.LayoutParams(1, dp(12)))

        val back = railButton("‹") {
            thread {
                if (AaInput.available) {
                    AaInput.back()
                } else {
                    try { AaDaemonClient.daemon?.goBack() } catch (_: Exception) {}
                }
            }
        }
        val backParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(88))
        backParams.bottomMargin = dp(16)
        column.addView(back, backParams)

        return column
    }

    private var currentAppView: ImageButton? = null

    private fun railButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = if (glyph == "‹") 28f else 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { onClick() }
        }

    private fun updateRecents() {
        val column = recentsColumn ?: return
        val recent = recentAppsState.recentApps.take(MAX_RECENTS)
        if (recent.joinToString() == lastRecent) return
        lastRecent = recent.joinToString()
        column.removeAllViews()
        val pm = packageManager
        for (pkg in recent) {
            val icon = iconCache[pkg] ?: run {
                val bmp = try {
                    (pm.getApplicationIcon(pkg) as? BitmapDrawable)?.bitmap
                } catch (_: Exception) { null }
                if (bmp != null) { iconCache[pkg] = bmp; bmp } else null
            } ?: continue
            val btn = ImageButton(this).apply {
                setImageBitmap(icon)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    thread { try { AaDaemonClient.daemon?.launchApp(pkg) } catch (_: Exception) {} }
                    scheduleAutoHide()
                }
            }
            column.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        }
    }

    @Volatile private var lastRecent = ""

    private fun expand() {
        if (expanded) return
        expanded = true
        val c = container ?: return
        c.setBackgroundColor(RAIL_BG)
        params?.let {
            it.width = dp(RAIL_WIDTH_DP)
            try { wm?.updateViewLayout(c, it) } catch (_: Exception) {}
        }
        setButtonsVisible(true)
        updateRecents()
        scheduleAutoHide()
        FileLog.i(TAG, "rail expanded")
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        handler.removeCallbacks(autoHide)
        val c = container ?: return
        params?.let {
            it.width = dp(HANDLE_DP)
            try { wm?.updateViewLayout(c, it) } catch (_: Exception) {}
        }
        c.setBackgroundColor(HANDLE_BG)
        setButtonsVisible(false)
        recentsColumn?.removeAllViews()
        lastRecent = ""
        FileLog.i(TAG, "rail collapsed")
    }

    private fun setButtonsVisible(visible: Boolean) {
        val c = container ?: return
        val v = if (visible) View.VISIBLE else View.GONE
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child !== recentsColumn) child.visibility = v
        }
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun goHome() {
        try { AaDaemonClient.daemon?.goHome() } catch (_: Exception) {}
        scheduleAutoHide()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                handler.post { if (expanded) collapse() else expand() }
                return START_STICKY
            }
            else -> return super.onStartCommand(intent, flags, startId)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pulse)
        handler.removeCallbacks(refreshRecents)
        handler.removeCallbacks(autoHide)
        container?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        container = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "NavRailService"
        private const val RAIL_WIDTH_DP = 74
        private const val HANDLE_DP = 10
        private const val MAX_RECENTS = 4
        private const val PULSE_INTERVAL_MS = 400L
        private const val AUTO_HIDE_MS = 5000L
        private const val HANDLE_BG = 0x660A0E14.toInt()
        private const val RAIL_BG = 0xF20A0E14.toInt()

        private const val ACTION_TOGGLE = "com.dilinkauto.client.NAVRAIL_TOGGLE"

        fun start(context: Context) {
            try {
                context.startService(Intent(context, NavRailService::class.java))
            } catch (e: Exception) {
                FileLog.w(TAG, "start failed: ${e.message}")
            }
        }

        fun toggle(context: Context) {
            try {
                context.startService(
                    Intent(context, NavRailService::class.java).setAction(ACTION_TOGGLE))
            } catch (e: Exception) {
                FileLog.w(TAG, "toggle failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavRailService::class.java))
        }
    }
}
