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
     * Create a VirtualDisplay with the given Surface.
     * Called from createVirtualDisplayFromTexture or externally.
     *
     * @return display ID, or -1 on failure
     */
    fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        val name = "DiLinkAutoVD"
        val flags = 0x6c49 // TRUSTED + OWN_DISPLAY_GROUP + OWN_FOCUS + PUBLIC + OWN_CONTENT_ONLY

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
            println("[NativeBridge] VD created via DisplayManager: id=$id ${width}x${height}@${dpi}dpi")
            try { setDisplayImePolicy(id) } catch (_: Exception) {}
            return id
        } catch (e: Exception) {
            System.err.println("[NativeBridge] DisplayManager failed: ${e.message}")
        }

        return -1
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
        } catch (_: Exception) {
            false
        }
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
            if (cmd.startsWith("pm ") || cmd.startsWith("cmd ")) {
                // For commands where we want output, use a separate process
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.trim()
            } else null
        } catch (_: Exception) { null }
    }

    /** Launch an app on the virtual display */
    fun launchApp(displayId: Int, packageName: String): Boolean {
        return try {
            val component = execShell(
                "cmd package resolve-activity --brief " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $packageName 2>/dev/null | tail -1")
                ?.trim()

            if (!component.isNullOrEmpty()) {
                execShell("am start --display $displayId -n $component")
            } else {
                execShell("am start --display $displayId " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER $packageName")
            }
            true
        } catch (_: Exception) { false }
    }
}
