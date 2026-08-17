package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Java bridge called from native code (JNI up-calls).
 *
 * Provides access to Java-only Android APIs:
 * - VirtualDisplay creation (reflection on DisplayManager)
 * - Input injection (IInputManager via ServiceManager reflection)
 * - Display power (DisplayControl via services.jar)
 * - Shell command execution
 *
 * All methods are called from native threads. The native code manages
 * thread safety through the single-threaded pipeline design.
 */
class NativeBridge {

    private var inputManager: Any? = null
    private var injectInputEventMethod: java.lang.reflect.Method? = null
    private var setDisplayIdMethod: java.lang.reflect.Method? = null
    private var displayControlClass: Class<*>? = null
    private var displayControlLoaded = false
    private var persistentShell: Process? = null
    private var shellOutput: java.io.OutputStream? = null
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private val activeVds = HashMap<Int, VirtualDisplay>()

    init {
        initInputManager()
        initPersistentShell()
    }

    fun updateTexImage() {
        surfaceTexture?.updateTexImage()
    }

    // ── VirtualDisplay Creation ──

    /**
     * Create a VirtualDisplay from a GL texture ID.
     * Creates SurfaceTexture(texId) + Surface, then creates VD with reflection.
     * Called from native code after EGL creates the GL texture.
     *
     * @return display ID, or -1 on failure
     */
    fun createVirtualDisplayFromTexture(texId: Int, width: Int, height: Int, dpi: Int): Int {
        val st = android.graphics.SurfaceTexture(texId)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st // store for updateTexImage calls from native pipeline
        val surface = Surface(st)
        return createVirtualDisplay(width, height, dpi, surface)
    }

    /**
     * Create a VirtualDisplay with the given Surface (default name "DiLinkAutoVD").
     *
     * @return display ID, or -1 on failure
     */
    fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        return createVirtualDisplay("DiLinkAutoVD", width, height, dpi, surface)
    }

    /**
     * Create a named VirtualDisplay with the given Surface.
     *
     * @return display ID, or -1 on failure
     */
    fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        lastVdWidth = width
        lastVdHeight = height
        val flags = 0x6849

        // Try DisplayManagerGlobal first (more reliable on newer Android)
        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                .invoke(null)

            val cfgClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val bldClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val bldCtor = bldClass.getDeclaredConstructor(
                String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            bldCtor.isAccessible = true
            val bld = bldCtor.newInstance(name, width, height, dpi)

            bldClass.getDeclaredMethod("setSurface", Surface::class.java)
                .apply { isAccessible = true; invoke(bld, surface) }
            bldClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType)
                .apply { isAccessible = true; invoke(bld, flags) }

            try {
                bldClass.getDeclaredMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true; invoke(bld, 0) }
            } catch (_: NoSuchMethodException) {}

            val cfg = bldClass.getDeclaredMethod("build")
                .apply { isAccessible = true }.invoke(bld)
            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")

            val createVd: java.lang.reflect.Method = try {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass,
                    android.os.Handler::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass,
                    String::class.java)
            }
            createVd.isAccessible = true

            val vd: VirtualDisplay? = if (createVd.parameterCount == 4) {
                createVd.invoke(dmg, cfg, null, null, "com.android.shell") as? VirtualDisplay
            } else {
                createVd.invoke(dmg, cfg, null, "com.android.shell") as? VirtualDisplay
            }

            if (vd != null) {
                val id = try { vd.display.displayId } catch (_: Exception) { findDisplayId(name) }
                activeVds[id] = vd
                println("[NativeBridge] VD created via DisplayManagerGlobal: id=$id ${width}x${height}@${dpi}dpi")
                try { setDisplayImePolicy(id) } catch (_: Exception) {}
                try { execShell("settings put global force_resizable_activities 1") } catch (_: Exception) {}
                return id
            }
        } catch (e: Exception) {
            System.err.println("[NativeBridge] DisplayManagerGlobal failed: ${e.message}")
        }

        // Fallback: DisplayManager
        try {
            val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true
            val dm = ctor.newInstance(FakeContext.get())

            try {
                DisplayManager::class.java.getDeclaredField("mDisplayIdToMirror")
                    .apply { isAccessible = true; setInt(dm, 0) }
            } catch (_: Exception) {}

            val dmFlags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or (1 shl 6) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 14))

            val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, dmFlags)
            val id = try { vd.display.displayId } catch (_: Exception) { findDisplayId(name) }
            activeVds[id] = vd
            println("[NativeBridge] VD created via DisplayManager: id=$id ${width}x${height}@${dpi}dpi")
            try { setDisplayImePolicy(id) } catch (_: Exception) {}
            return id
        } catch (e: Exception) {
            System.err.println("[NativeBridge] DisplayManager failed: ${e.message}")
        }

        return -1
    }

    /** Release a specific VirtualDisplay by id (idempotent). */
    fun releaseVirtualDisplay(displayId: Int) {
        val vd = activeVds.remove(displayId) ?: return
        try { clearForcedDisplaySize(displayId) } catch (_: Exception) {}
        try { vd.release() } catch (_: Exception) {}
        println("[NativeBridge] VD released: id=$displayId")
    }

    /** Release all active VirtualDisplays (idempotent). */
    fun releaseVirtualDisplay() {
        activeVds.forEach { (id, vd) ->
            try { clearForcedDisplaySize(id) } catch (_: Exception) {}
            try { vd.release() } catch (_: Exception) {}
            println("[NativeBridge] VD released: id=$id")
        }
        activeVds.clear()
        lastVdWidth = 0
        lastVdHeight = 0
    }

    private fun findDisplayId(name: String): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "dumpsys display 2>/dev/null | grep -A 5 '$name' | grep 'mDisplayId=' | head -1"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            Regex("mDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun setDisplayImePolicy(id: Int) {
        try {
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String::class.java)
                    .invoke(null, "window"))
            wm.javaClass.getDeclaredMethod("setDisplayImePolicy",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, id, 0)
        } catch (_: Exception) {}
    }

    // ── Overscan ──

    /**
     * Apply overscan insets to a display via reflection on IWindowManager.
     *
     * Android 12+ (API 31): IWindowManager.setOverscan(displayId, left, top,
     * right, bottom). The method is @hide — accessed through ServiceManager +
     * Stub.asInterface reflection, same pattern as [setDisplayImePolicy].
     *
     * On ROMs that removed the method (e.g. HyperOS), this logs an explicit
     * warning and returns false — the caller can fall back or ignore.
     *
     * @return true if the call was dispatched successfully
     */
    fun setOverscan(displayId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return try {
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String::class.java)
                    .invoke(null, "window"))
            wm.javaClass.getDeclaredMethod("setOverscan",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
                .invoke(wm, displayId, left, top, right, bottom)
            println("[NativeBridge] setOverscan display=$displayId left=$left top=$top right=$right bottom=$bottom")
            true
        } catch (e: NoSuchMethodException) {
            System.err.println("[NativeBridge] setOverscan NOT available on this ROM (method missing)")
            false
        } catch (e: Exception) {
            System.err.println("[NativeBridge] setOverscan failed: ${e.message}")
            false
        }
    }

    // ── Forced Display Size ──

    /**
     * Force the content area of a display to a specific size via reflection on
     * DisplayManagerGlobal.setForcedDisplaySize (AOSP @hide).
     *
     * @return true if the call was dispatched successfully
     */
    fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): Boolean {
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                .invoke(null)
            dmgClass.getDeclaredMethod("setForcedDisplaySize",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(dmg, displayId, width, height)
            println("[NativeBridge] setForcedDisplaySize display=$displayId ${width}x${height}")
            true
        } catch (e: NoSuchMethodException) {
            System.err.println("[NativeBridge] setForcedDisplaySize NOT available on this ROM (method missing)")
            false
        } catch (e: Exception) {
            System.err.println("[NativeBridge] setForcedDisplaySize failed: ${e.message}")
            false
        }
    }

    /**
     * Clear any forced display size previously applied via [setForcedDisplaySize].
     */
    fun clearForcedDisplaySize(displayId: Int): Boolean {
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                .invoke(null)
            dmgClass.getDeclaredMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(dmg, displayId)
            println("[NativeBridge] clearForcedDisplaySize display=$displayId")
            true
        } catch (e: NoSuchMethodException) {
            System.err.println("[NativeBridge] clearForcedDisplaySize NOT available on this ROM (method missing)")
            false
        } catch (e: Exception) {
            System.err.println("[NativeBridge] clearForcedDisplaySize failed: ${e.message}")
            false
        }
    }

    /**
     * Force display size via the `wm size` shell command (fallback when the
     * reflection path is unavailable — e.g. HyperOS).
     */
    fun setForcedDisplaySizeViaShell(displayId: Int, width: Int, height: Int): Boolean {
        return try {
            execShell("wm size ${width}x${height} -d $displayId")
            println("[NativeBridge] setForcedDisplaySizeViaShell display=$displayId ${width}x${height}")
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] setForcedDisplaySizeViaShell failed: ${e.message}")
            false
        }
    }

    /**
     * Clear forced display size via `wm size reset` shell command.
     */
    fun clearForcedDisplaySizeViaShell(displayId: Int): Boolean {
        return try {
            execShell("wm size reset -d $displayId")
            println("[NativeBridge] clearForcedDisplaySizeViaShell display=$displayId")
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] clearForcedDisplaySizeViaShell failed: ${e.message}")
            false
        }
    }

    // ── Input Injection ──

    private fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            injectInputEventMethod = inputManager!!.javaClass
                .getMethod("injectInputEvent", android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType)
            try {
                setDisplayIdMethod = MotionEvent::class.java
                    .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            System.err.println("[NativeBridge] InputManager init failed: ${e.message}")
        }
    }

    /**
     * Inject a touch event. Called from native touch reader thread.
     *
     * @param actionDesc Format: "action,pointerId,x,y,pressure,displayId"
     *        action: 0=DOWN, 1=MOVE, 2=UP
     */
    /** Set when shell-uid injection is denied (HyperOS) — use root CLI paths. */
    @Volatile var shellInjectionBlocked = false
        private set

    fun injectMotionEvent(displayId: Int, action: Int, desc: String): Boolean {
        if (inputManager == null || injectInputEventMethod == null) {
            // Fallback: shell-based tap
            val parts = desc.split(",")
            if (parts.size >= 4) {
                val x = parts[2]; val y = parts[3]
                try { execShell("input -d $displayId tap $x $y") } catch (_: Exception) {}
            }
            return false
        }

        if (setDisplayIdMethod == null) {
            // HyperOS: no setDisplayId — IInputManager would go to display 0
            // silently. Use root CLI instead.
            val parts = desc.split(",")
            if (parts.size >= 4) {
                val x = parts[2]; val y = parts[3]
                try {
                    execShell("input -d $displayId tap $x $y")
                    return true
                } catch (_: Exception) {}
            }
            return false
        }

        return try {
            val parts = desc.split(",")
            val pointerId = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val x = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
            val pressure = parts.getOrNull(4)?.toFloatOrNull() ?: 1f

            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; this.pressure = pressure; size = 1f
            })

            val ma = when (action) {
                0 -> MotionEvent.ACTION_DOWN
                2 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }

            val ev = MotionEvent.obtain(now, now, ma, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.invoke(ev, displayId)
            injectInputEventMethod!!.invoke(inputManager, ev, 0)
            ev.recycle()
            true
        } catch (e: Exception) {
            // HyperOS blocks shell input injection into virtual displays
            // (INJECT_EVENTS denied even for uid 2000) — caller falls back
            // to root CLI injection.
            shellInjectionBlocked = true
            System.err.println("[NativeBridge] injectMotionEvent failed: ${e.message}")
            false
        }
    }

    /**
     * Inject a two-pointer (pinch) event step.
     *
     * @param phase 0=DOWN, 1=MOVE, 2=UP. Pointer ids are fixed to 0 and 1.
     *   Android requires ACTION_DOWN before ACTION_POINTER_DOWN, so DOWN emits
     *   two events (DOWN for pointer 0, then POINTER_DOWN for pointer 1);
     *   UP mirrors that (POINTER_UP for pointer 1, then UP for pointer 0).
     */
    fun injectTwoPointerEvent(displayId: Int, phase: Int,
                              x1: Float, y1: Float,
                              x2: Float, y2: Float): Boolean {
        if (inputManager == null || injectInputEventMethod == null) {
            System.err.println("[NativeBridge] injectTwoPointerEvent: no InputManager")
            return false
        }
        return try {
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = x1; y = y1; pressure = 1f; size = 1f
                },
                MotionEvent.PointerCoords().apply {
                    x = x2; y = y2; pressure = 1f; size = 1f
                }
            )
            when (phase) {
                0 -> {
                    inject(displayId, now, MotionEvent.ACTION_DOWN, 1, props, coords)
                    inject(displayId, now, MotionEvent.ACTION_POINTER_DOWN or
                        (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords)
                }
                2 -> {
                    inject(displayId, now, MotionEvent.ACTION_POINTER_UP or
                        (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords)
                    inject(displayId, now, MotionEvent.ACTION_UP, 1, props, coords)
                }
                else -> inject(displayId, now, MotionEvent.ACTION_MOVE, 2, props, coords)
            }
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] injectTwoPointerEvent failed: ${e.message}")
            false
        }
    }

    private fun inject(displayId: Int, downTime: Long, action: Int, count: Int,
                       props: Array<MotionEvent.PointerProperties>,
                       coords: Array<MotionEvent.PointerCoords>) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            count, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        setDisplayIdMethod?.invoke(ev, displayId)
        injectInputEventMethod!!.invoke(inputManager, ev, 0)
        ev.recycle()
    }

    /**
     * Tap via root `input` CLI. Needed on HyperOS, where shell cannot inject
     * into virtual displays (root bypasses the INJECT_EVENTS check). A tap
     * already includes DOWN+UP, so callers must only invoke this once per click.
     */
    fun injectTapViaRoot(displayId: Int, x: Int, y: Int): Boolean {
        return try {
            execShell("su -c 'input -d $displayId tap $x $y'")
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] injectTapViaRoot failed: ${e.message}")
            false
        }
    }

    /** Key event via root `input` CLI — same HyperOS block as [injectTapViaRoot]. */
    fun injectKeyViaRoot(displayId: Int, keyCode: Int): Boolean {
        return try {
            execShell("su -c 'input -d $displayId keyevent $keyCode'")
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] injectKeyViaRoot failed: ${e.message}")
            false
        }
    }

    /**
     * Key event straight through IInputManager — unlike [injectKeyViaRoot],
     * no per-press process spawn (~300ms saved per Back/Home). DOWN+UP pair.
     * Falls back to the plain `input` CLI when the binder path is unavailable.
     */
    fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean {
        if (inputManager == null || injectInputEventMethod == null) {
            try { execShell("input -d $displayId keyevent $keyCode") } catch (_: Exception) {}
            return false
        }
        return try {
            val now = SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,
                keyCode, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP,
                keyCode, 0)
            // InputEvent.setDisplayId is @hide — reflect per-event in case the
            // method is absent on some OEM builds (cached setDisplayIdMethod
            // is MotionEvent-typed; KeyEvent needs its own lookup).
            val setDisplay = try {
                down.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (_: Exception) { null }
            setDisplay?.invoke(down, displayId)
            setDisplay?.invoke(up, displayId)
            injectInputEventMethod!!.invoke(inputManager, down, 0)
            injectInputEventMethod!!.invoke(inputManager, up, 0)
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] injectKeyEvent failed: ${e.message}")
            try { execShell("input -d $displayId keyevent $keyCode") } catch (_: Exception) {}
            false
        }
    }

    /**
     * True when the display's only visible task is our own launcher — pressing
     * Back there would empty the stack and leave a black VD (the daemon's
     * relaunch watcher is unreachable on HyperOS).
     */
    fun isLauncherOnlyTask(displayId: Int): Boolean {
        val dumpsys = execShellOutput("dumpsys activity activities 2>/dev/null") ?: return false
        val marker = "Display #$displayId "
        val start = dumpsys.indexOf(marker)
        if (start < 0) return false
        val next = dumpsys.indexOf("Display #", start + marker.length)
        val section = if (next >= 0) dumpsys.substring(start, next) else dumpsys.substring(start)
        val tasks = Regex("Task\\{[0-9a-f]+ #\\d+ type=\\S+ (?:A=\\d+:)?(\\S+) U=")
            .findAll(section).map { it.groupValues[1] }.toList()
        return tasks.isNotEmpty() && tasks.all { it.contains("dilinkauto") }
    }

    // ── Display Power ──

    fun setDisplayPower(on: Boolean): Boolean {
        return try {
            if (!displayControlLoaded) {
                try {
                    displayControlClass = Class.forName(
                        "com.android.server.display.DisplayControl")
                    displayControlLoaded = true
                } catch (_: Exception) {
                    try {
                        val clf = Class.forName("dalvik.system.DelegateLastClassLoader")
                            .getDeclaredConstructor(String::class.java, String::class.java,
                                ClassLoader::class.java)
                        clf.isAccessible = true
                        displayControlClass = (clf.newInstance(
                            "/system/framework/services.jar", null,
                            ClassLoader.getSystemClassLoader()) as ClassLoader)
                            .loadClass("com.android.server.display.DisplayControl")
                        displayControlLoaded = true
                    } catch (_: Exception) {
                        System.err.println("[NativeBridge] DisplayControl load failed")
                    }
                }
            }

            val cls = displayControlClass
            if (cls != null) {
                val gid = cls.getDeclaredMethod("getPhysicalDisplayIds")
                    .apply { isAccessible = true }
                val ids = gid.invoke(null) as LongArray
                val sp = cls.getDeclaredMethod("setDisplayPowerMode",
                    IBinder::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                for (id in ids) {
                    sp.invoke(null, cls.getDeclaredMethod("getPhysicalDisplayToken",
                        Long::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(null, id), if (on) 2 else 0)
                }
            }
            true
        } catch (_: Exception) {
            try { execShell("cmd display power-${if (on) "on" else "off"} 0") } catch (_: Exception) {}
            true
        }
    }

    // ── Shell Commands ──

    private fun initPersistentShell() {
        try {
            persistentShell = Runtime.getRuntime().exec(arrayOf("sh"))
            shellOutput = persistentShell!!.outputStream
        } catch (e: Exception) {
            System.err.println("[NativeBridge] Shell init failed: ${e.message}")
        }
    }

    /** Fire-and-forget shell command */
    fun execShell(cmd: String): String? {
        return try {
            shellOutput?.let {
                it.write("$cmd\n".toByteArray())
                it.flush()
            }
            if (cmd.startsWith("pm ") || cmd.startsWith("cmd ") ||
                cmd.startsWith("dumpsys ") || cmd.startsWith("am task ")) {
                // For commands where we want output, use a separate process
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.trim()
            } else null
        } catch (_: Exception) { null }
    }

    /** Run a command in a separate process and capture stdout. */
    fun execShellOutput(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (_: Exception) { null }
    }

    /**
     * True if the display has no visible activity task (empty back-stack).
     * Finished root tasks can linger as `visible=false` shells after the last
     * activity exits, so emptiness = no task with visible=true.
     */
    fun isDisplayStackEmpty(displayId: Int): Boolean {
        val dumpsys = execShellOutput("dumpsys activity activities 2>/dev/null") ?: return false
        val marker = "Display #$displayId "
        val start = dumpsys.indexOf(marker)
        if (start < 0) return true
        val next = dumpsys.indexOf("Display #", start + marker.length)
        val section = if (next >= 0) dumpsys.substring(start, next) else dumpsys.substring(start)
        return section.lines().none { it.contains("Task{") && it.contains("visible=true") }
    }

    /** Launch an app fullscreen on the virtual display. The nav rail is a
     *  transient swipe-in overlay — no freeform, no per-app padding. */
    fun launchApp(displayId: Int, packageName: String): Boolean {
        return try {
            val component = execShell(
                "cmd package resolve-activity --brief " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $packageName 2>/dev/null | tail -1")
                ?.trim()
            if (component.isNullOrEmpty()) {
                System.err.println("[NativeBridge] launchApp $packageName: no component")
                return false
            }
            // Force landscape: app-compat override makes the app follow the
            // display's orientation (VD is landscape) regardless of its
            // requested orientation — same mechanism Screen Orientation
            // Control uses. MIUI's size-compat ignores the AOSP display flag.
            execShell("am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER $packageName")

            // --activity-multiple-task: force a NEW task on the VD. Without it,
            // apps that already have a task on the physical display (singleTask
            // or plain recents) resume THERE instead of opening on the VD.
            val out = execShell("am start --display $displayId --activity-multiple-task -n $component")
            System.err.println("[NativeBridge] launchApp $packageName fullscreen on display $displayId: $out")
            true
        } catch (e: Exception) {
            System.err.println("[NativeBridge] launchApp $packageName failed: ${e.message}")
            false
        }
    }

    @Volatile private var lastVdWidth = 0
    @Volatile private var lastVdHeight = 0
}
