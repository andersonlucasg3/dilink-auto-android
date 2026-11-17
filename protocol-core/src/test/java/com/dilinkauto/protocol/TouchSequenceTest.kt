package com.dilinkauto.protocol

import org.junit.Assert.*
import org.junit.Test

class TouchSequenceTest {

    /**
     * Exact state machine replicated from PipelineServer.kt lines 432-477
     */
    class PipelineServerTouchStateMachine(val maxPointers: Int = 10) {
        val activePointers = LinkedHashMap<Int, FloatArray>()
        val propsPool = Array(maxPointers) { PointerProps() }
        val coordsPool = Array(maxPointers) { PointerCoords() }
        var touchDownTime = 0L

        data class PointerProps(var id: Int = 0, var toolType: Int = 1)
        data class PointerCoords(var x: Float = 0f, var y: Float = 0f, var pressure: Float = 0f, var size: Float = 1f)

        data class EmittedEvent(
            val downTime: Long,
            val eventTime: Long,
            val action: Int,
            val actionMasked: Int,
            val actionIndex: Int,
            val pointerCount: Int,
            val activeIds: List<Int>
        )

        val emittedEvents = mutableListOf<EmittedEvent>()
        var lastException: Throwable? = null

        // MotionEvent constants
        companion object {
            const val ACTION_DOWN = 0
            const val ACTION_UP = 1
            const val ACTION_MOVE = 2
            const val ACTION_POINTER_DOWN = 5
            const val ACTION_POINTER_UP = 6
            const val ACTION_MASK = 0xFF
            const val ACTION_POINTER_INDEX_MASK = 0xFF00
            const val ACTION_POINTER_INDEX_SHIFT = 8
        }

        fun injectTouch(action: Int, ptr: Int, x: Int, y: Int, pressure: Float, now: Long): Boolean {
            try {
                activePointers[ptr] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
                if (activePointers.isEmpty()) return false
                val pts = activePointers.entries.toList()
                val ptrIndex = pts.indexOfFirst { it.key == ptr }
                if (ptrIndex < 0) return false

                for ((i, e) in pts.withIndex()) {
                    val k = e.key
                    val v = e.value
                    propsPool[i] = (propsPool[i] ?: PointerProps()).also {
                        it.id = k
                        it.toolType = 1
                    }
                    coordsPool[i] = (coordsPool[i] ?: PointerCoords()).also {
                        it.x = v[0]
                        it.y = v[1]
                        it.pressure = v[2]
                        it.size = 1f
                    }
                }

                val ma = when (action) {
                    0 -> {
                        touchDownTime = now
                        if (pts.size == 1) ACTION_DOWN
                        else ACTION_POINTER_DOWN or (ptrIndex shl ACTION_POINTER_INDEX_SHIFT)
                    }
                    2 -> {
                        if (pts.size == 1) ACTION_UP
                        else ACTION_POINTER_UP or (ptrIndex shl ACTION_POINTER_INDEX_SHIFT)
                    }
                    else -> ACTION_MOVE
                }

                val actionMasked = ma and ACTION_MASK
                val actionIndex = (ma and ACTION_POINTER_INDEX_MASK) shr ACTION_POINTER_INDEX_SHIFT

                emittedEvents.add(
                    EmittedEvent(
                        downTime = touchDownTime,
                        eventTime = now,
                        action = ma,
                        actionMasked = actionMasked,
                        actionIndex = actionIndex,
                        pointerCount = pts.size,
                        activeIds = pts.map { it.key }
                    )
                )

                if (action == 2) {
                    activePointers.remove(ptr)
                }
                return true
            } catch (e: Throwable) {
                lastException = e
                return false
            }
        }
    }

    @Test
    fun testSingleTouchSequence_DownMoveUp() {
        val sm = PipelineServerTouchStateMachine()

        // 1. DOWN
        assertTrue(sm.injectTouch(action = 0, ptr = 0, x = 100, y = 200, pressure = 1.0f, now = 1000L))
        // 2. MOVE
        assertTrue(sm.injectTouch(action = 1, ptr = 0, x = 110, y = 210, pressure = 1.0f, now = 1016L))
        // 3. UP
        assertTrue(sm.injectTouch(action = 2, ptr = 0, x = 110, y = 210, pressure = 1.0f, now = 1032L))

        assertEquals(3, sm.emittedEvents.size)

        // Event 0: ACTION_DOWN
        val ev0 = sm.emittedEvents[0]
        assertEquals(PipelineServerTouchStateMachine.ACTION_DOWN, ev0.actionMasked)
        assertEquals(0, ev0.actionIndex)
        assertEquals(1, ev0.pointerCount)
        assertEquals(listOf(0), ev0.activeIds)

        // Event 1: ACTION_MOVE
        val ev1 = sm.emittedEvents[1]
        assertEquals(PipelineServerTouchStateMachine.ACTION_MOVE, ev1.actionMasked)
        assertEquals(1, ev1.pointerCount)

        // Event 2: ACTION_UP
        val ev2 = sm.emittedEvents[2]
        assertEquals(PipelineServerTouchStateMachine.ACTION_UP, ev2.actionMasked)
        assertEquals(1, ev2.pointerCount)
        assertEquals(listOf(0), ev2.activeIds)

        // Ensure activePointers was cleared
        assertTrue(sm.activePointers.isEmpty())
    }

    @Test
    fun testMultiTouchSequence_StandardOrder() {
        // Sequence: DOWN(0) -> MOVE(0) -> POINTER_DOWN(1) -> MOVE(0,1) -> POINTER_UP(1) -> UP(0)
        val sm = PipelineServerTouchStateMachine()

        sm.injectTouch(action = 0, ptr = 0, x = 100, y = 100, pressure = 1.0f, now = 1000L)
        sm.injectTouch(action = 1, ptr = 0, x = 105, y = 105, pressure = 1.0f, now = 1016L)
        sm.injectTouch(action = 0, ptr = 1, x = 300, y = 300, pressure = 1.0f, now = 1032L)
        sm.injectTouch(action = 1, ptr = 0, x = 110, y = 110, pressure = 1.0f, now = 1048L)
        sm.injectTouch(action = 2, ptr = 1, x = 300, y = 300, pressure = 1.0f, now = 1064L)
        sm.injectTouch(action = 2, ptr = 0, x = 110, y = 110, pressure = 1.0f, now = 1080L)

        assertEquals(6, sm.emittedEvents.size)

        // Event 0: ACTION_DOWN
        assertEquals(PipelineServerTouchStateMachine.ACTION_DOWN, sm.emittedEvents[0].actionMasked)
        assertEquals(1, sm.emittedEvents[0].pointerCount)

        // Event 1: ACTION_MOVE
        assertEquals(PipelineServerTouchStateMachine.ACTION_MOVE, sm.emittedEvents[1].actionMasked)
        assertEquals(1, sm.emittedEvents[1].pointerCount)

        // Event 2: ACTION_POINTER_DOWN for ptr 1 (index 1)
        val ev2 = sm.emittedEvents[2]
        assertEquals(PipelineServerTouchStateMachine.ACTION_POINTER_DOWN, ev2.actionMasked)
        assertEquals(1, ev2.actionIndex)
        assertEquals(2, ev2.pointerCount)
        assertEquals(listOf(0, 1), ev2.activeIds)

        // Event 3: ACTION_MOVE
        val ev3 = sm.emittedEvents[3]
        assertEquals(PipelineServerTouchStateMachine.ACTION_MOVE, ev3.actionMasked)
        assertEquals(2, ev3.pointerCount)

        // Event 4: ACTION_POINTER_UP for ptr 1 (index 1)
        val ev4 = sm.emittedEvents[4]
        assertEquals(PipelineServerTouchStateMachine.ACTION_POINTER_UP, ev4.actionMasked)
        assertEquals(1, ev4.actionIndex)
        assertEquals(2, ev4.pointerCount)
        assertEquals(listOf(0, 1), ev4.activeIds)

        // Event 5: ACTION_UP for remaining ptr 0
        val ev5 = sm.emittedEvents[5]
        assertEquals(PipelineServerTouchStateMachine.ACTION_UP, ev5.actionMasked)
        assertEquals(1, ev5.pointerCount)
        assertEquals(listOf(0), ev5.activeIds)

        assertTrue(sm.activePointers.isEmpty())
    }

    @Test
    fun testMultiTouchSequence_ReverseReleaseOrder() {
        // Sequence: DOWN(0) -> POINTER_DOWN(1) -> POINTER_UP(0) -> MOVE(1) -> UP(1)
        val sm = PipelineServerTouchStateMachine()

        sm.injectTouch(action = 0, ptr = 0, x = 100, y = 100, pressure = 1f, now = 1000L)
        sm.injectTouch(action = 0, ptr = 1, x = 200, y = 200, pressure = 1f, now = 1016L)
        // Release pointer 0 first!
        sm.injectTouch(action = 2, ptr = 0, x = 100, y = 100, pressure = 1f, now = 1032L)
        // Move pointer 1
        sm.injectTouch(action = 1, ptr = 1, x = 210, y = 210, pressure = 1f, now = 1048L)
        // Release pointer 1
        sm.injectTouch(action = 2, ptr = 1, x = 210, y = 210, pressure = 1f, now = 1064L)

        assertEquals(5, sm.emittedEvents.size)

        // Event 2: POINTER_UP for pointer 0 (index 0)
        val ev2 = sm.emittedEvents[2]
        assertEquals(PipelineServerTouchStateMachine.ACTION_POINTER_UP, ev2.actionMasked)
        assertEquals(0, ev2.actionIndex)
        assertEquals(2, ev2.pointerCount)
        assertEquals(listOf(0, 1), ev2.activeIds)

        // Event 3: MOVE for pointer 1 (activeIds = [1], pointerCount = 1)
        val ev3 = sm.emittedEvents[3]
        assertEquals(PipelineServerTouchStateMachine.ACTION_MOVE, ev3.actionMasked)
        assertEquals(1, ev3.pointerCount)
        assertEquals(listOf(1), ev3.activeIds)

        // Event 4: ACTION_UP for pointer 1 (activeIds = [1], pointerCount = 1)
        val ev4 = sm.emittedEvents[4]
        assertEquals(PipelineServerTouchStateMachine.ACTION_UP, ev4.actionMasked)
        assertEquals(1, ev4.pointerCount)
        assertEquals(listOf(1), ev4.activeIds)

        assertTrue(sm.activePointers.isEmpty())
    }

    @Test
    fun testStress_MoreThanMaxPointers_IsValidWireData_InjectorMustCap() {
        // The wire format has no pointer-count limit (TouchMoveBatch accepts up
        // to 255); the 10-pointer cap belongs to the injection side. Before the
        // S-09 fix, vd-server's TouchInjector let >10 pointers overflow its
        // fixed propsPool and swallowed the ArrayIndexOutOfBoundsException,
        // jamming touch for the rest of the session — the old version of this
        // test asserted exactly that jam as "expected".
        //
        // Now: TouchInjector caps activePointers (vd-server PointerCapTest, 8
        // cases) and rejects out-of-range pointer ids before pool indexing.
        // What remains true at the protocol layer is that an 11-pointer batch is
        // still well-formed wire data — the receiver must cap, not the wire.
        val batch = TouchMoveBatch((0..10).map { i ->
            TouchEvent(
                action = InputMsg.TOUCH_MOVE,
                pointerId = i,
                x = i / 11f,
                y = i / 11f,
                pressure = 1f,
                timestamp = 1200L + i
            )
        })
        val decoded = TouchMoveBatch.decode(batch.encode())
        assertEquals(11, decoded.pointers.size)
        assertEquals(10, decoded.pointers.last().pointerId)
    }
}
