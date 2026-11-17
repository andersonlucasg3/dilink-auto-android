package com.dilinkauto.vdserver

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.dilinkauto.protocol.*
import java.io.OutputStream
import java.lang.reflect.Method

/**
 * Touch injection into the phone's VirtualDisplay via the InputManager service.
 *
 * Built once per server lifetime; [handleTouchFrame] decodes incoming touch
 * frames and routes them to [injectTouch], which either uses the InputManager
 * reflection path (preferred, ~zero latency) or the `input -d` shell fallback
 * when the reflection path is unavailable.
 *
 * Multi-touch state ([activePointers], [propsPool], [coordsPool]) lives here
 * so the input side stays isolated from the GL/encode pipeline.
 *
 * [activePointers] is capped at [MAX_POINTERS] (audit S-09): pointerId arrives
 * unvalidated, so the table must stay bounded and index-safe no matter what the
 * wire carries. See [PointerCap] for the eviction policy.
 *
 * issue #60：批量 MOVE 由 [injectMoveBatch] 一次发出，不再逐指针重复构造事件。
 *
 * Extracted from [PipelineServer].
 */
internal class TouchInjector(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val displayIdProvider: () -> Int,
    private val shellProvider: () -> OutputStream?,
    private val logErr: (String) -> Unit,
    /** issue #64：成功路径的诊断日志（带时钟，由 PipelineServer 注入）。 */
    private val logInfo: (String) -> Unit = {}
) {
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private var touchDownTime = 0L

    /** issue #64：触控链路计数与节流日志，见 [TouchDiagnostics]。 */
    private val diag = TouchDiagnostics(logInfo)

    /** Whether the InputManager reflection path is wired and ready. */
    val hasInjection: Boolean get() = inputManager != null && injectInputEventMethod != null

    /** 一次批量 MOVE 里的单个指针更新（坐标已换算成 VD 像素）。 */
    private data class PointerUpdate(val pointerId: Int, val x: Int, val y: Int, val pressure: Float)

    /**
     * Reflect into the InputManager service. Failure is non-fatal — touch
     * falls back to `input -d` shell taps. Safe to call once at startup.
     */
    fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as android.os.IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            injectInputEventMethod = inputManager!!.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            try { setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (_: Exception) {}
            PipeLog.log("InputManager ready")
        } catch (e: Exception) { logErr("InputManager: ${e.message}") }
    }

    /** Decode a touch frame and inject it. */
    fun handleTouchFrame(f: FrameCodec.Frame) {
        val now = SystemClock.uptimeMillis()
        diag.noteFrame()
        when (f.messageType) {
            InputMsg.TOUCH_MOVE_BATCH -> {
                val b = TouchMoveBatch.decode(f.payload)
                injectMoveBatch(b.pointers.map {
                    PointerUpdate(it.pointerId, (it.x * displayWidth).toInt(), (it.y * displayHeight).toInt(), it.pressure)
                }, now)
            }
            InputMsg.TOUCH_DOWN, InputMsg.TOUCH_MOVE, InputMsg.TOUCH_UP -> {
                val e = TouchEvent.decode(f.payload)
                val action = when (f.messageType) {
                    InputMsg.TOUCH_DOWN -> ACTION_DOWN
                    InputMsg.TOUCH_MOVE -> ACTION_MOVE
                    else -> ACTION_UP
                }
                injectTouch(action, e.pointerId, (e.x * displayWidth).toInt(), (e.y * displayHeight).toInt(), e.pressure, now)
            }
        }
    }

    /**
     * issue #60：整批 MOVE 只发一条 MotionEvent，携带全部指针的新坐标。
     *
     * 旧实现把批次里的每个指针各调一次 [injectTouch]，每次都构造一条「包含全部
     * activePointers、但只有当前指针是新坐标」的 MOVE：同一次采样被拆成多条，中间态
     * 还是上一帧的旧坐标，缩放之类的多指手势容易被判成抖动而触发 ACTION_CANCEL。
     *
     * 另外：任一 DOWN 丢失（网络/队列丢帧）后该 pointer 不会进 [activePointers]，
     * 后续 MOVE 会退化成单指；这里遇到「没见过的 pointer 在 MOVE」时先补一条
     * ACTION_POINTER_DOWN，避免一根手指整段失效。
     */
    private fun injectMoveBatch(updates: List<PointerUpdate>, nowMs: Long) {
        if (updates.isEmpty()) return
        // 反射通道不可用时 MOVE 无法表达（`input -d tap` 只有点按），保持旧行为：不落命令。
        if (inputManager == null || injectInputEventMethod == null) return
        val displayId = displayIdProvider()
        try {
            val missing = TouchBatchPlan.pointersMissingDown(
                active = activePointers.keys,
                moving = updates.map { it.pointerId },
                maxPointers = MAX_POINTERS,
            )
            if (missing.isNotEmpty()) {
                val byId = updates.associateBy { it.pointerId }
                for (id in missing) {
                    val u = byId[id] ?: continue
                    logErr("Touch: pointerId=$id moved without DOWN — synthesizing ACTION_POINTER_DOWN")
                    injectTouch(ACTION_DOWN, id, u.x, u.y, u.pressure, nowMs, synthesized = true)
                }
            }
            for (u in updates) {
                if (u.pointerId !in 0 until MAX_POINTERS) continue
                noteBounds(u.pointerId, u.x, u.y, nowMs)
                PointerCap.put(activePointers, u.pointerId, floatArrayOf(u.x.toFloat(), u.y.toFloat(), u.pressure), MAX_POINTERS) { evicted ->
                    logErr("Touch: >$MAX_POINTERS active pointers — evicting oldest pointerId=$evicted")
                }
            }
            diag.noteMoveBatch(nowMs, updates.map { Triple(it.pointerId, it.x, it.y) })
            val pts = snapshotIntoPools() ?: return
            emit(MotionEvent.ACTION_MOVE, pts.size, displayId)
        } catch (e: Exception) {
            logErr("Touch batch: ${e.message}")
        }
    }

    private fun injectTouch(
        action: Int,
        ptr: Int,
        x: Int,
        y: Int,
        pressure: Float,
        nowMs: Long,
        synthesized: Boolean = false,
    ) {
        val displayId = displayIdProvider()
        if (inputManager == null || injectInputEventMethod == null) {
            // issue #64：回退路径也记录按下/抬起，至少能看到事件到达了手机。
            when (action) {
                ACTION_DOWN -> if (synthesized) diag.noteSynthDown(nowMs, ptr, x, y) else diag.noteDown(nowMs, ptr, x, y, displayId)
                ACTION_UP -> diag.noteUp(nowMs, ptr, x, y, displayId)
            }
            try { if (action == ACTION_DOWN || action == ACTION_UP) execShell("input -d $displayId tap $x $y") } catch (_: Exception) {}
            return
        }
        // S-09: pointerId is an unvalidated Int off the wire. The protocol encoder only
        // ever emits 0..MAX_POINTERS-1, but a corrupt or hostile frame can carry anything,
        // and propsPool/coordsPool are indexed by insertion order over the map below.
        // Reject the frame here instead of discovering it as an out-of-bounds index.
        if (ptr !in 0 until MAX_POINTERS) {
            logErr("Touch: dropping frame with pointerId=$ptr (expected 0 until $MAX_POINTERS)")
            diag.noteRejectedId()
            return
        }
        try {
            noteBounds(ptr, x, y, nowMs)
            // issue #64：成功路径逐条记录 DOWN/UP；MOVE 太高频，只计数。
            when (action) {
                ACTION_DOWN -> if (synthesized) diag.noteSynthDown(nowMs, ptr, x, y) else diag.noteDown(nowMs, ptr, x, y, displayId)
                ACTION_UP -> diag.noteUp(nowMs, ptr, x, y, displayId)
            }
            // S-09: cap the table. activePointers only ever shed entries on UP, so one
            // dropped UP packet (or a frame with a bogus id) used to grow it forever —
            // and past MAX_POINTERS entries propsPool[i] threw inside the publish loop,
            // which the catch below swallowed, jamming touch for the whole session.
            // The cap runs before the insert, so it never evicts the pointer of THIS frame.
            PointerCap.put(activePointers, ptr, floatArrayOf(x.toFloat(), y.toFloat(), pressure), MAX_POINTERS) { evicted ->
                logErr("Touch: >$MAX_POINTERS active pointers — evicting oldest pointerId=$evicted")
            }
            val pts = snapshotIntoPools() ?: return
            val ptrIndex = pts.indexOfFirst { it.key == ptr }
            if (ptrIndex < 0) return

            val ma = when (action) {
                ACTION_DOWN -> {
                    touchDownTime = SystemClock.uptimeMillis()
                    if (pts.size == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN or (ptrIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                ACTION_UP -> {
                    if (pts.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (ptrIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                else -> MotionEvent.ACTION_MOVE
            }

            emit(ma, pts.size, displayId)
        } catch (e: Exception) {
            // S-09: no longer silent. A dropped exception here used to leave the frame
            // half-applied (map written, event never injected) with nothing in the log.
            logErr("Touch: ${e.message}")
        } finally {
            // S-09: UP is terminal — the pointer leaves the table whether or not the
            // injection succeeded. Skipping this on the failure path is what produced
            // permanently ghost pointers.
            if (action == ACTION_UP) activePointers.remove(ptr)
        }
    }

    /**
     * 把 [activePointers] 快照进 propsPool/coordsPool，返回按索引顺序排列的指针列表。
     *
     * S-09：两个 pool 只有 MAX_POINTERS 个槽位，必须截断后再索引，超长表才不会越界。
     */
    private fun snapshotIntoPools(): List<Map.Entry<Int, FloatArray>>? {
        val pts = activePointers.entries.take(MAX_POINTERS)
        if (pts.isEmpty()) return null
        for ((i, e) in pts.withIndex()) {
            val v = e.value
            propsPool[i] = propsPool[i].also {
                it.id = e.key
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coordsPool[i] = coordsPool[i].also {
                it.x = v[0]
                it.y = v[1]
                it.pressure = v[2]
                it.size = 1f
            }
        }
        return pts
    }

    /** 用当前 pool 内容发一条事件；失败只记日志，不影响后续帧。 */
    private fun emit(ma: Int, count: Int, displayId: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(touchDownTime, now, ma, count, propsPool, coordsPool, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.invoke(ev, displayId)
            injectInputEventMethod!!.invoke(inputManager, ev, 0)
            ev.recycle()
        } catch (e: Exception) {
            diag.noteInjectFail()
            logErr("Touch inject failed: ${e.message}")
        }
    }

    /**
     * issue #64：映射到 VD 像素后的坐标越界（`x == width` / 负值等）。
     *
     * 注入本身不改行为（系统仍会自行裁剪），只落节流日志 —— 「某些页面顶部点不动」
     * 的现场需要先排除「坐标根本不在 VD 内」这个前提。
     */
    private fun noteBounds(ptr: Int, x: Int, y: Int, nowMs: Long) {
        if (x < 0 || y < 0 || x >= displayWidth || y >= displayHeight) {
            diag.noteOutOfBounds(nowMs, ptr, x, y, displayWidth, displayHeight)
        }
    }

    private fun execShell(cmd: String) = ShellExec.execShell(shellProvider(), cmd)

    internal companion object {
        /** Multi-touch state bounds. Wire TouchEvent encoder emits pointerId in 0 until MAX_POINTERS. */
        const val MAX_POINTERS = 10 /* matches protocol TouchEvent encoder */

        /** Local aliases for the wire actions handled here (see [handleTouchFrame]). */
        private const val ACTION_DOWN = 0
        private const val ACTION_MOVE = 1
        private const val ACTION_UP = 2
    }
}
