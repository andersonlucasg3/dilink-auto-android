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
 *
 * Long-press: the SurfaceCallback has no long-press callback, so a static
 * hold is emulated by touch-slop suppression in [scrollBy] — while a held
 * finger only produces sub-slop jitter deltas, no MOVE is forwarded and the
 * pointer stays DOWN at the anchor, letting Android's own long-press
 * detection fire in the VD app.
 */
object AaInput {

    private const val TAG = "AaInput"
    private const val HOST = "127.0.0.1"
    private const val PORT = 19648
    private const val CONNECT_TIMEOUT_MS = 200
    private const val SPAWN_WAIT_MS = 500L
    private const val SPAWN_RETRIES = 3
    private const val GESTURE_END_DEBOUNCE_MS = 300L
    // Host digitizers emit tiny jitter deltas as onScroll while a finger is
    // held down; below this slop we treat them as a static hold (long-press).
    private const val TOUCH_SLOP_PX = 24f
    private const val FLING_DURATION_S = 0.15f
    private const val FLING_STEPS = 4
    private const val FLING_STEP_DELAY_MS = 16L
    private const val PINCH_SEED_SPREAD = 200f
    private const val PINCH_MIN_SPREAD = 40f
    private const val PINCH_MAX_SPREAD = 2000f

    /** True when app-side root injection can be used. */
    val available: Boolean get() = RootManager.isAvailable && targetDisplayId >= 0

    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var sentDisplayId = -1
    private var targetDisplayId = -1

    /** Set which display touch/key events target. Call before any gesture.
     *  Bug #1/#2 fix: switching displays resets gesture state so a stale
     *  drag position from one VD doesn't bleed into another. */
    fun setTargetDisplay(displayId: Int) {
        if (displayId != targetDisplayId) {
            synchronized(lock) {
                dragEndFuture?.cancel(false)
                pinchEndFuture?.cancel(false)
                curX = -1f
                curY = -1f
                dragActive = false
                pinchActive = false
            }
        }
        targetDisplayId = displayId
    }

    // ── Atomic gesture methods (setTargetDisplay + gesture in one lock) ──
    // Bug #2 fix: prevents MirrorScreen and Vd2Viewport from overwriting
    // targetDisplayId between the setTargetDisplay call and the gesture's
    // synchronized(lock) block.
    //
    // Bug #3 fix: all methods return Boolean — true when the command was
    // sent to the injector, false on failure. Callers can use this for
    // fallback logic (e.g. MirrorScreen falling back to daemon.touch).

    /** Atomic: setTargetDisplay + tap in one lock. Returns true on success. */
    fun tapOn(displayId: Int, x: Int, y: Int): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            return sendLocked("tap $x $y")
        }
    }

    /** Atomic: setTargetDisplay + downAt in one lock. Returns true on success. */
    fun downAtOn(displayId: Int, x: Int, y: Int): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            if (dragActive) endDragLocked()
            curX = clampX(x.toFloat())
            curY = clampY(y.toFloat())
            dragAnchorX = curX
            dragAnchorY = curY
            slopExceeded = true
            val ok = sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = true
            return ok
        }
    }

    /** Atomic: setTargetDisplay + moveBy in one lock. Returns true on success,
     *  false when no drag is active or the write failed. */
    fun moveByOn(displayId: Int, dx: Float, dy: Float): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!dragActive) return false
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            curX = clampX(curX + dx)
            curY = clampY(curY - dy)
            val ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            dragEndFuture?.cancel(false)
            dragEndFuture = debouncer.schedule({
                synchronized(lock) {
                    if (dragActive) {
                        sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                        dragActive = false
                    }
                }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
        }
    }

    /** Atomic: setTargetDisplay + upAt in one lock. Returns true on success,
     *  false when no drag is active or the write failed. */
    fun upAtOn(displayId: Int): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!dragActive) return false
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            dragEndFuture?.cancel(false)
            val ok = sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
            return ok
        }
    }

    /** Atomic: setTargetDisplay + scrollBy in one lock. Returns true on success.
     *  Y-direction: AA onScroll reports scroll-content deltas (positive dy =
     *  content scrolled up, finger moved down). We invert here so the virtual
     *  touch moves opposite to scroll direction — content scrolls as expected.
     *  If drag feels inverted on a particular car, flip the sign on curY. */
    fun scrollByOn(displayId: Int, dx: Float, dy: Float): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            var ok = true
            if (!dragActive) {
                seedPositionLocked()
                dragAnchorX = curX
                dragAnchorY = curY
                slopExceeded = false
                ok = sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            curX = clampX(curX + dx)
            curY = clampY(curY - dy)
            if (!slopExceeded) {
                val ddx = curX - dragAnchorX
                val ddy = curY - dragAnchorY
                if (ddx * ddx + ddy * ddy <= TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                    dragEndFuture?.cancel(false)
                    dragEndFuture = debouncer.schedule({
                        synchronized(lock) {
                            if (dragActive) {
                                sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                                dragActive = false
                            }
                        }
                    }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                    return ok
                }
                slopExceeded = true
            }
            ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            dragEndFuture?.cancel(false)
            dragEndFuture = debouncer.schedule({
                synchronized(lock) {
                    if (dragActive) {
                        sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                        dragActive = false
                    }
                }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
        }
    }

    /** Atomic: setTargetDisplay + fling in one lock. Returns true on success. */
    fun flingOn(displayId: Int, vx: Float, vy: Float): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            seedPositionLocked()
            val stepX = vx * FLING_DURATION_S / FLING_STEPS
            val stepY = vy * FLING_DURATION_S / FLING_STEPS
            dragEndFuture?.cancel(false)
            if (!dragActive) {
                slopExceeded = true
                sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            var ok = true
            repeat(FLING_STEPS) {
                curX = clampX(curX + stepX)
                curY = clampY(curY + stepY)
                ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            }
            sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
            return ok
        }
    }

    /** Atomic: setTargetDisplay + scale in one lock. Returns true on success. */
    fun scaleOn(displayId: Int, focusX: Float, focusY: Float, factor: Float): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (dragActive) endDragLocked()
            pinchFocusX = clampX(focusX)
            pinchFocusY = clampY(focusY)
            var ok = true
            if (!pinchActive) {
                pinchSpread = PINCH_SEED_SPREAD
                val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
                val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
                ok = sendLocked("mdown $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
                pinchActive = true
            }
            pinchSpread = (pinchSpread * factor).coerceIn(PINCH_MIN_SPREAD, PINCH_MAX_SPREAD)
            val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
            val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
            ok = sendLocked("mmove $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
            pinchEndFuture?.cancel(false)
            pinchEndFuture = debouncer.schedule({
                synchronized(lock) { endPinchLocked() }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
        }
    }

    /** Atomic: setTargetDisplay + key injection in one lock. Returns true on success,
     *  false when the injector is unavailable or the write failed. */
    fun keyOn(displayId: Int, keyCode: Int): Boolean {
        synchronized(lock) {
            switchDisplayLocked(displayId)
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            return sendLocked("key $keyCode")
        }
    }

    // ── Internal helpers ──

    /** Inline display-switch reset — lock already held.
     *  When the target display changes, we reset all in-progress gesture state
     *  (cancel debounce timers, invalidate pointer position, clear active flags).
     *  We intentionally do NOT send UP/MUP to the old display: display switches
     *  only occur at gesture boundaries (after debounce ended the previous
     *  gesture), so the old display's injector already received its UP. */
    private fun switchDisplayLocked(displayId: Int) {
        if (displayId != targetDisplayId) {
            dragEndFuture?.cancel(false)
            pinchEndFuture?.cancel(false)
            curX = -1f
            curY = -1f
            dragActive = false
            pinchActive = false
        }
        targetDisplayId = displayId
    }

    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    // Gesture state — guarded by lock
    private var curX = -1f
    private var curY = -1f
    private var dragActive = false
    // Cumulative displacement from the drag anchor — slop suppression tracks
    // the total drag distance, not the per-delta position.
    private var dragAnchorX = 0f
    private var dragAnchorY = 0f
    private var slopExceeded = false
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

    /** Blocking — call from an IO dispatcher. Returns true on success. */
    fun tap(x: Int, y: Int): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            return sendLocked("tap $x $y")
        }
    }

    /** Blocking — call from an IO dispatcher. Returns true on success. */
    fun key(keyCode: Int): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            return sendLocked("key $keyCode")
        }
    }

    /**
     * Back that no-ops when the VD's only visible task is the launcher —
     * pressing Back there would empty the stack and leave a black VD.
     * The guard runs injector-side (`back` command), so no `su` spawn here.
     * Returns true when the command was sent to the injector.
     */
    fun back(): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            return sendLocked("back")
        }
    }

    /**
     * Spawn/connect the root input injector ahead of the first tap — the
     * spawn can take 0.5–1.5s, which the user would otherwise pay on their
     * first touch. Blocking — call from a background thread.
     */
    fun warmUp() {
        synchronized(lock) { ensureLocked(); Unit }
    }

    /**
     * Start a drag at an explicit position — for viewport-to-VD2 forwarding
     * where the caller already has the mapped coordinates. Sets the tracked
     * pointer position and sends DOWN. Must be paired with [upAt].
     * Blocking — call from an IO dispatcher. Returns true on success.
     */
    fun downAt(x: Int, y: Int): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            if (dragActive) endDragLocked()
            curX = clampX(x.toFloat())
            curY = clampY(y.toFloat())
            dragAnchorX = curX
            dragAnchorY = curY
            slopExceeded = true // caller handles slop; we start the drag now
            val ok = sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = true
            return ok
        }
    }

    /**
     * Move the active drag by (dx, dy) — no seed, no slop gate (drag was
     * already started by [downAt]). No-ops when no drag is active.
     * Blocking — call from an IO dispatcher. Returns true on success,
     * false when no drag is active or the write failed.
     */
    fun moveBy(dx: Float, dy: Float): Boolean {
        synchronized(lock) {
            if (!dragActive) return false
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            curX = clampX(curX + dx)
            // Y-inversion: caller passes raw touch deltas; we invert here so
            // upward finger movement scrolls the content down (same as scrollBy).
            curY = clampY(curY - dy)
            val ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            // Keep the debounce alive
            dragEndFuture?.cancel(false)
            dragEndFuture = debouncer.schedule({
                synchronized(lock) {
                    if (dragActive) {
                        sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                        dragActive = false
                    }
                }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
        }
    }

    /** End the drag started by [downAt]. Blocking — call from an IO dispatcher.
     *  Returns true on success, false when no drag is active or the write failed. */
    fun upAt(): Boolean {
        synchronized(lock) {
            if (!dragActive) return false
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            dragEndFuture?.cancel(false)
            val ok = sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
            return ok
        }
    }

    /**
     * Drag by (dx, dy) from the tracked pointer position. The first call starts
     * the drag (DOWN); 300ms without a further call ends it (UP).
     *
     * Touch-slop suppression: while the cumulative displacement from the drag
     * anchor stays below [TOUCH_SLOP_PX], no MOVE is forwarded — the pointer
     * stays DOWN at the anchor. A held finger only generates jitter deltas, so
     * Android's own long-press detection fires in the VD app; a real scroll
     * crosses the slop and the accumulated position is forwarded from there.
     * Blocking — call from an IO dispatcher. Returns true on success.
     */
    fun scrollBy(dx: Float, dy: Float): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            var ok = true
            if (!dragActive) {
                seedPositionLocked()
                dragAnchorX = curX
                dragAnchorY = curY
                slopExceeded = false
                ok = sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            curX = clampX(curX + dx)
            // AA onScroll Y direction is inverted vs the VD's touch coords
            curY = clampY(curY - dy)
            if (!slopExceeded) {
                val ddx = curX - dragAnchorX
                val ddy = curY - dragAnchorY
                if (ddx * ddx + ddy * ddy <= TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                    // Still jitter — stay DOWN at the anchor, but keep the
                    // debounce alive so the hold is not released.
                    dragEndFuture?.cancel(false)
                    dragEndFuture = debouncer.schedule({
                        synchronized(lock) {
                            if (dragActive) {
                                sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                                dragActive = false
                            }
                        }
                    }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                    return ok
                }
                slopExceeded = true
            }
            ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            dragEndFuture?.cancel(false)
            dragEndFuture = debouncer.schedule({
                synchronized(lock) {
                    if (dragActive) {
                        sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
                        dragActive = false
                    }
                }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
        }
    }

    /**
     * Swipe along the velocity vector (distance = velocity * 0.15s, clamped).
     * Extends an active drag, otherwise runs a short down→moves→up swipe.
     * Blocking — call from an IO dispatcher. Returns true on success.
     */
    fun fling(vx: Float, vy: Float): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (pinchActive) endPinchLocked()
            seedPositionLocked()
            val stepX = vx * FLING_DURATION_S / FLING_STEPS
            val stepY = vy * FLING_DURATION_S / FLING_STEPS
            dragEndFuture?.cancel(false)
            if (!dragActive) {
                // Fling always travels — bypass the slop gate outright.
                slopExceeded = true
                sendLocked("down 0 ${curX.toInt()} ${curY.toInt()}")
                dragActive = true
            }
            var ok = true
            repeat(FLING_STEPS) {
                curX = clampX(curX + stepX)
                curY = clampY(curY + stepY)
                ok = sendLocked("move 0 ${curX.toInt()} ${curY.toInt()}")
            }
            sendLocked("up 0 ${curX.toInt()} ${curY.toInt()}")
            dragActive = false
            return ok
        }
    }

    /**
     * Pinch around (focusX, focusY): two pointers start at focus ± spread/2
     * horizontally (spread seeded at 200px) and move apart/together by
     * scaleFactor. 300ms without a further call ends the pinch (UP).
     * Blocking — call from an IO dispatcher. Returns true on success.
     */
    fun scale(focusX: Float, focusY: Float, factor: Float): Boolean {
        synchronized(lock) {
            if (!ensureLocked()) return false
            ensureDisplayLocked()
            if (dragActive) endDragLocked()
            pinchFocusX = clampX(focusX)
            pinchFocusY = clampY(focusY)
            var ok = true
            if (!pinchActive) {
                pinchSpread = PINCH_SEED_SPREAD
                val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
                val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
                ok = sendLocked("mdown $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
                pinchActive = true
            }
            pinchSpread = (pinchSpread * factor).coerceIn(PINCH_MIN_SPREAD, PINCH_MAX_SPREAD)
            val x1 = clampX(pinchFocusX - pinchSpread / 2).toInt()
            val x2 = clampX(pinchFocusX + pinchSpread / 2).toInt()
            ok = sendLocked("mmove $x1 ${pinchFocusY.toInt()} $x2 ${pinchFocusY.toInt()}")
            pinchEndFuture?.cancel(false)
            pinchEndFuture = debouncer.schedule({
                synchronized(lock) { endPinchLocked() }
            }, GESTURE_END_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            return ok
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
        socket?.let { s ->
            if (s.isConnected && !s.isClosed && writer != null) {
                // Bug #2 fix: probe write to detect silently-dead peer.
                // isConnected/isClosed only report local state — a TCP
                // RST from a dead injector goes undetected until the next
                // real write. One flush() catches it early.
                try {
                    writer!!.flush()
                    return true
                } catch (_: Exception) {
                    FileLog.w(TAG, "injector socket dead — reconnecting")
                    closeLocked()
                    // Fall through to reconnect
                }
            }
        }
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
            s.soTimeout = 500        // 500ms read timeout
            s.setSoLinger(true, 0)   // close imediato
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

    /** Push the current target display id to the injector when it changed. */
    private fun ensureDisplayLocked() {
        val id = targetDisplayId
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
            FileLog.d(TAG, "sent: $cmd")
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
