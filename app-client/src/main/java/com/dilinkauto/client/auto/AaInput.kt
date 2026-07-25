package com.dilinkauto.client.auto

import com.dilinkauto.client.FileLog
import com.dilinkauto.client.RootManager
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * App-side input injection into the AA virtual display.
 *
 * Why this exists: the daemon runs as shell (uid 2000), and HyperOS denies
 * shell the INJECT_EVENTS permission for virtual displays — and KernelSU
 * hides `su` from the daemon's shell context, so the daemon cannot escalate
 * either. The APP holds the root grant, so it spawns a persistent root
 * app_process ("input injector", see vd-server InputInjectorMain) that owns
 * IInputManager injection and receives gesture commands over a localhost
 * socket (127.0.0.1:19648). A persistent process is required because the
 * root `input` CLI has no multi-pointer support (pinch impossible) and
 * costs one process spawn per event (drag impossible).
 *
 * Only used when root is available; other backends fall back to the daemon's
 * shell injection (fine on AOSP/emulators). All public methods are blocking —
 * call from an IO dispatcher. Writes are serialized on a single lock.
 */
object AaInput {

    private const val TAG = "AaInput"
    private const val HOST = "127.0.0.1"
    private const val PORT = 19648
    private const val CONNECT_TIMEOUT_MS = 200
    private const val SPAWN_WAIT_MS = 500L
    private const val SPAWN_RETRIES = 3
    private const val GESTURE_END_DEBOUNCE_MS = 300L
    private const val FLING_DURATION_S = 0.15f
    private const val FLING_STEPS = 4
    private const val FLING_STEP_DELAY_MS = 16L
    private const val PINCH_SEED_SPREAD = 200f
    private const val PINCH_MIN_SPREAD = 40f
    private const val PINCH_MAX_SPREAD = 2000f

    /** True when app-side root injection can be used. */
    val available: Boolean get() = RootManager.isAvailable && AaDaemonClient.displayId >= 0

    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var sentDisplayId = -1

    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    // Gesture state — guarded by lock
    private var curX = -1f
    private var curY = -1f
    private var dragActive = false
    private var pinchActive = false
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchSpread = PINCH_SEED_SPREAD

    private val debouncer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AaInputDebounce").apply { isDaemon = true }
    }
    private var dragEndFuture: ScheduledFuture<*>? = null
    private var pinchEndFuture: ScheduledFuture<*>? = null

    /** Called by MirrorScreen when the AA surface (re)appears. */
    fun setSurfaceSize(w: Int, h: Int) {
        surfaceW = w
        surfaceH = h
    }

    /** Blocking — call from an IO dispatcher. */
    fun tap(x: Int, y: Int) {
        synchronized(lock) {
            if (!ensureLocked()) return
            ensureDisplayLocked()
            sendLocked("tap $x $y")
        }
    }

    /** Blocking — call from an IO dispatcher. */
    fun key(keyCode: Int) {
        synchronized(lock) {
            if (!ensureLocked()) return
            ensureDisplayLocked()
            sendLocked("key $keyCode")
        }
    }

    /**
     * Back that no-ops when the VD's only visible task is the launcher —
     * pressing Back there would empty the stack and leave a black VD
     * (the daemon's relaunch watcher is unreachable on HyperOS).
     */
    fun back() {
        val id = AaDaemonClient.displayId
        if (id >= 0 && isLauncherAlone(id)) {
            FileLog.i(TAG, "back blocked — launcher is the only task on display $id")
            return
        }
        key(4)
    }

    private fun isLauncherAlone(displayId: Int): Boolean {
        return try {
            val out = RootManager.execAndWait(
                "dumpsys activity activities | grep -e \"Display #$displayId \" -A4 | head -12") ?: return false
            val tasks = Regex("Task\\{[0-9a-f]+ #\\d+ type=\\S+ (?:A=\\d+:)?(\\S+) U=")
                .findAll(out).map { it.groupValues[1] }.toList()
            tasks.isNotEmpty() && tasks.all { it.contains("dilinkauto") }
        } catch (_: Exception) { false }
    }

    /**
     * Drag by (dx, dy) from the tracked pointer position. The first call starts
     * the drag (DOWN); 300ms without a further call ends it (UP).
     * Blocking — call from an IO dispatcher.
     */
    fun scrollBy(dx: Float, dy: Float) {
        synchronized(lock) {
            if (!ensureLocked()) return
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            if (!dragActive) {
                seedPositionLocked()
                sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            curX = clampX(curX + dx)
            // AA onScroll Y direction is inverted vs the VD's touch coords
            curY = clampY(curY - dy)
            sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            dragEndFuture?.cancel(false)
            dragEndFuture = debouncer.schedule({
                synchronized(lock) {
                    if (dragActive) {
                        sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                        dragActive = false
                    }
                }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Swipe along the velocity vector (distance = velocity * 0.15s, clamped).
     * Extends an active drag, otherwise runs a short down→moves→up swipe.
     * Blocking — call from an IO dispatcher.
     */
    fun fling(vx: Float, vy: Float) {
        synchronized(lock) {
            if (!ensureLocked()) return
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            seedPositionLocked()
            val stepX = vx * FLING_DURATION_S / FLING_STEPS
            val stepY = vy * FLING_DURATION_S / FLING_STEPS
            dragEndFuture?.cancel(false)
            if (!dragActive) {
                sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            repeat(FLING_STEPS) {
                curX = clampX(curX + stepX)
                curY = clampY(curY + stepY)
                sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
                Thread.sleep(FLING_STEP_DELAY_MS)
            }
            sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
        }
    }

    /**
     * Pinch around (focusX, focusY): two pointers start at focus ± spread/2
     * horizontally (spread seeded at 200px) and move apart/together by
     * scaleFactor. 300ms without a further call ends the pinch (UP).
     * Blocking — call from an IO dispatcher.
     */
    fun scale(focusX: Float, focusY: Float, factor: Float) {
        synchronized(lock) {
            if (!ensureLocked()) return
            ensureDisplayLocked()
            if (dragActive) endDragLocked()
            pinchFocusX = clampX(focusX)
            pinchFocusY = clampY(focusY)
            if (!pinchActive) {
                pinchSpread = PINCH_SEED_SPREAD
                val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
                val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
                sendLocked("mdown $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
                pinchActive = true
            }
            pinchSpread = (pinchSpread * factor).coerceIn(PINCH_MIN_SPREAD, PINCH_MAX_SPREAD)
            val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
            val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
            sendLocked("mmove $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
            pinchEndFuture?.cancel(false)
            pinchEndFuture = debouncer.schedule({
                synchronized(lock) { endPinchLocked() }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ── Gesture helpers (lock held) ──

    private fun endDragLocked() {
        dragEndFuture?.cancel(false)
        if (dragActive) {
            sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
        }
    }

    private fun endPinchLocked() {
        pinchEndFuture?.cancel(false)
        if (pinchActive) {
            val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
            val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
            sendLocked("mup $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
            pinchActive = false
        }
    }

    /** Seed the tracked pointer position: center of the surface on first use. */
    private fun seedPositionLocked() {
        // Re-anchor to the center whenever the tracked pointer drifted
        // off-screen — a new drag must START at a valid on-screen point.
        val offScreen = (surfaceW > 0 && (curX < 0f || curX >= surfaceW)) ||
            (surfaceH > 0 && (curY < 0f || curY >= surfaceH)) ||
            (curX < 0f || curY < 0f)
        if (offScreen) {
            curX = if (surfaceW > 0) surfaceW / 2f else 0f
            curY = if (surfaceH > 0) surfaceH / 2f else 0f
        }
    }

    // Pointers may legitimately travel off-screen during a drag — clamping to
    // the surface kills scrolling at the edge. Allow one screen of overshoot.
    private fun clampX(x: Float) = if (surfaceW > 0) x.coerceIn(-surfaceW.toFloat(), 2f * surfaceW) else x
    private fun clampY(y: Float) = if (surfaceH > 0) y.coerceIn(-surfaceH.toFloat(), 2f * surfaceH) else y

    // ── Socket handling (lock held) ──

    /** Connect to the injector, spawning it as root when it is not running. */
    private fun ensureLocked(): Boolean {
        if (!available) return false
        socket?.let { if (it.isConnected && !it.isClosed && writer != null) return true }
        closeLocked()

        if (connectLocked()) return true

        // Not running — spawn as root. RootManager.execAndWait already wraps
        // the command in `su -c`; setsid + & detaches the process so the
        // shell returns immediately (same pattern as DaemonDeployer).
        FileLog.i(TAG, "spawning root input injector on :$PORT")
        RootManager.execAndWait(
            "setsid env CLASSPATH=/data/local/tmp/vd-server.jar app_process / " +
            "com.dilinkauto.vdserver.DaemonEntry input-injector" +
            " >/data/local/tmp/input-injector.log 2>&1 &")
        repeat(SPAWN_RETRIES) { attempt ->
            Thread.sleep(SPAWN_WAIT_MS)
            if (connectLocked()) return true
            FileLog.w(TAG, "injector connect attempt ${attempt + 1}/$SPAWN_RETRIES failed")
        }
        FileLog.w(TAG, "input injector unavailable — see /data/local/tmp/input-injector.log")
        return false
    }

    private fun connectLocked(): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            sentDisplayId = -1
            FileLog.i(TAG, "connected to input injector")
            true
        } catch (_: Exception) {
            closeLocked()
            false
        }
    }

    /** Push the current VD display id to the injector when it changed. */
    private fun ensureDisplayLocked() {
        val id = AaDaemonClient.displayId
        if (id >= 0 && id != sentDisplayId) {
            if (sendLocked("display $id")) sentDisplayId = id
        }
    }

    /** Returns false when the write failed (socket closed; next call re-ensures). */
    private fun sendLocked(cmd: String): Boolean {
        val w = writer ?: return false
        return try {
            w.write(cmd)
            w.newLine()
            w.flush()
            true
        } catch (e: Exception) {
            FileLog.w(TAG, "injector write failed ($cmd): ${e.message}")
            closeLocked()
            false
        }
    }

    private fun closeLocked() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        sentDisplayId = -1
    }
}
