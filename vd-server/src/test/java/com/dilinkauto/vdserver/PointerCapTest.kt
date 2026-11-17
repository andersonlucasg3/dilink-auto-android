package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pointer-table eviction policy added for S-09.
 *
 * `TouchInjector`'s `activePointers` only sheds entries on UP, so a single dropped
 * UP packet grows it forever, and once it passes `propsPool`/`coordsPool`'s length
 * (10) the publish loop threw ArrayIndexOutOfBoundsException that the caller's empty
 * catch swallowed — jammed touch for the whole session. [PointerCap] keeps the table
 * bounded and index-safe.
 *
 * This mirrors the behaviour the protocol-core `PipelineServerTouchStateMachine`
 * stress test locks in ("more than max pointers demonstrates the jam") — that test
 * now documents the bug rather than the fix.
 */
class PointerCapTest {

    private val maxPointers = TouchInjector.MAX_POINTERS

    private fun tableOf(vararg ids: Int) = LinkedHashMap<Int, FloatArray>().also { m ->
        ids.forEach { m[it] = floatArrayOf(it.toFloat(), it.toFloat(), 1f) }
    }

    @Test
    fun thePoolSizeAndTheWireRangeAgree() {
        // propsPool/coordsPool are Array(MAX_POINTERS) and the wire encoder only emits
        // pointerId 0..MAX_POINTERS-1. If these drift, indices go out of bounds again.
        assertEquals(10, TouchInjector.MAX_POINTERS)
        assertTrue("index bound must cover the wire range", TouchInjector.MAX_POINTERS - 1 <= 9)
    }

    @Test
    fun aTableBelowTheCapTakesANewPointerWithoutEvicting() {
        val m = tableOf(0, 1, 2)
        val dropped = mutableListOf<Int>()
        PointerCap.put(m, 3, floatArrayOf(9f, 9f, 1f), maxPointers) { dropped += it }
        assertEquals(listOf(0, 1, 2, 3), m.keys.toList())
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun aFullTableWithAnAlreadyActivePointerDoesNotEvict() {
        val m = tableOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val dropped = mutableListOf<Int>()
        PointerCap.put(m, 5, floatArrayOf(9f, 9f, 1f), maxPointers) { dropped += it }
        assertEquals("table must stay at the cap", maxPointers, m.size)
        assertEquals("an already-active pointer must not trigger eviction", emptyList<Int>(), dropped)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), m.keys.toList())
    }

    @Test
    fun aBrandNewPointerPastTheCapEvictsTheOldestNotTheNewcomer() {
        val m = tableOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val dropped = mutableListOf<Int>()
        PointerCap.put(m, 10, floatArrayOf(9f, 9f, 1f), maxPointers) { dropped += it }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), m.keys.toList())
        assertEquals(listOf(0), dropped)
        assertTrue("the newcomer must survive, or its own DOWN frame is lost", m.containsKey(10))
    }

    @Test
    fun evictionNeverRemovesThePointerOfTheCurrentFrame() {
        // Degenerate cap (0) with a *foreign* pointer in the table: the helper evicts
        // what it can and then breaks out, rather than looping forever or leaving the
        // caller holding a frame whose own pointer was just dropped.
        val m = tableOf(5)
        val dropped = mutableListOf<Int>()
        PointerCap.put(m, 7, floatArrayOf(1f, 2f, 1f), 0) { dropped += it }
        assertTrue("the newcomer must survive, or its own frame is lost", m.containsKey(7))
        assertEquals(listOf(5), dropped)
        assertEquals(1, m.size)
    }

    @Test
    fun anOversizedTableIsShrunkBackToTheCapByTheNextInsert() {
        // The old jam could leave more than MAX_POINTERS entries behind; the cap
        // must be able to shrink such a table back.
        val m = tableOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        PointerCap.put(m, 12, floatArrayOf(1f, 1f, 1f), maxPointers) { }
        assertTrue("table must not exceed the cap, was ${m.size}", m.size <= maxPointers)
        assertTrue(m.containsKey(12))
    }

    @Test
    fun rePuttingAnExistingPointerOnlyUpdatesItsCoordinates() {
        val m = tableOf(0, 1)
        PointerCap.put(m, 1, floatArrayOf(5f, 6f, 0.5f), maxPointers) { }
        assertEquals(arrayOf(5f, 6f, 0.5f).toList(), m[1]!!.toList())
        assertEquals(listOf(0, 1), m.keys.toList())
    }

    @Test
    fun everyEvictionIsReportedSoItIsNeverSilent() {
        // Audit: the swallow-and-say-nothing behaviour is what made S-09 undebuggable.
        val m = tableOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val reported = mutableListOf<Int>()
        PointerCap.put(m, 10, floatArrayOf(0f, 0f, 1f), maxPointers) { reported += it }
        assertFalse("an eviction must be reported to the caller", reported.isEmpty())
        assertEquals(1, reported.size)
    }
}
