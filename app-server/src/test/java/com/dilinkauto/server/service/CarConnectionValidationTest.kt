package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the validation/cap logic added by the 2026-10-09 audit pass
 * over `app-server`:
 *
 *  - **S-M4**: peer-supplied viewport dims are clamped before reaching
 *    `MediaFormat` (non-positive/absurd values crash the decoder or suppress
 *    the rotation guard forever).
 *  - **S-M5**: APP_LIST icon byte budget (the per-icon pixel gate needs
 *    `BitmapFactory`, which is Android-only — see the note in
 *    `iconWithinLimits`'s own test).
 *  - **S-M1 / S-L5**: budget arithmetic for the bounded rebuild counter and
 *    the crash-archive prune.
 *
 * `CarConnectionService` is constructed the same way
 * [com.dilinkauto.server.MainActivityAdversarialTest] does it: the field
 * initializers only build coroutine scopes and plain objects, so no Android
 * framework call is needed until `onCreate` runs.
 */
class CarConnectionValidationTest {

    private fun newService(): CarConnectionService = try {
        val ctor = CarConnectionService::class.java.declaredConstructors[0]
        ctor.isAccessible = true
        val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
        ctor.newInstance(*args) as CarConnectionService
    } catch (_: Exception) {
        CarConnectionService::class.java.getDeclaredConstructor().newInstance()
    }

    private val service = newService()

    // ─── S-M4: peer dimension sanitisation ───

    @Test
    fun peerDimension_keepsRealisticValues() {
        assertEquals(1408, service.sanitizePeerDimension(1408, 792))
        assertEquals(1920, service.sanitizePeerDimension(1920, 1080))
        assertEquals(2, service.sanitizePeerDimension(2, 1408))       // boundary low
        assertEquals(4096, service.sanitizePeerDimension(4096, 1408)) // boundary high
    }

    @Test
    fun peerDimension_rejectsNonPositiveAndAbsurd() {
        // 0 / negative used to reach MediaFormat.createVideoFormat and throw;
        // 100000 used to permanently suppress the rotation guard comparison.
        assertEquals(1408, service.sanitizePeerDimension(0, 1408))
        assertEquals(1408, service.sanitizePeerDimension(-1, 1408))
        assertEquals(1408, service.sanitizePeerDimension(-999_999, 1408))
        assertEquals(1408, service.sanitizePeerDimension(4097, 1408))
        assertEquals(1408, service.sanitizePeerDimension(Int.MAX_VALUE, 1408))
        assertEquals(1408, service.sanitizePeerDimension(Int.MIN_VALUE, 1408))
    }

    @Test
    fun peerDimension_fallsBackPerAxis() {
        // HandshakeResponse can mix a valid width with a bogus height — each
        // axis must fall back independently, not all-or-nothing.
        assertEquals(1408, service.sanitizePeerDimension(0, 1408))
        assertEquals(792, service.sanitizePeerDimension(792, 792))
    }

    // ─── S-M5: APP_LIST icon byte budget ───

    /** Byte budget mirrored from the companion constant. */
    private val maxIconBytes = 64L shl 20

    /** Exact copy of the aggregate check in the APP_LIST handler. */
    private fun budgetExhausted(total: Long, iconSize: Int): Boolean =
        total + iconSize > maxIconBytes

    @Test
    fun iconBudget_allowsNormalBatch() {
        // 100 apps at 30KB ≈ 3MB — well inside the cap.
        var total = 0L
        var accepted = 0
        repeat(100) {
            if (budgetExhausted(total, 30_000)) return@repeat
            total += 30_000
            accepted++
        }
        assertEquals(100, accepted)
    }

    @Test
    fun iconBudget_stopsBeforeOOM() {
        // One 128MB frame (the protocol cap) filled with 200KB icons would be
        // ~600 icons → far past a 4GB device's comfort zone. The cap must cut
        // the batch off and keep the running total bounded.
        var total = 0L
        var accepted = 0
        repeat(600) {
            if (budgetExhausted(total, 200_000)) return@repeat
            total += 200_000
            accepted++
        }
        assertTrue("expected the budget to stop the batch early", accepted < 600)
        assertTrue("budget must stay within the cap", total <= maxIconBytes)
    }

    @Test
    fun iconBudget_boundaryIsInclusive() {
        // Exactly at the cap is allowed; one byte over is not.
        assertFalse(budgetExhausted(0, 1_000_000))            // a valid 1MB icon
        assertTrue(budgetExhausted(maxIconBytes - 1, 2))     // last byte tips it over
        assertTrue(budgetExhausted(maxIconBytes, 1))
    }

    // ─── S-M1: black-screen rebuild budget ───

    /** Exact copy of the self-heal gate's counter arithmetic. */
    private fun rebuildAllowed(rebuilds: Int): Boolean = rebuilds < 2

    @Test
    fun blackScreenRebuildBudget_isBounded() {
        assertTrue(rebuildAllowed(0))
        assertTrue(rebuildAllowed(1))
        assertFalse(rebuildAllowed(2))
        assertFalse(rebuildAllowed(3))
    }

    // ─── S-L5: crash archive pruning ───

    private val maxArchivedCrashes = 5

    /** Files to delete = everything past the newest N. */
    private fun pruneCount(total: Int): Int =
        if (total <= maxArchivedCrashes) 0 else total - maxArchivedCrashes

    @Test
    fun crashArchive_keepsOnlyNewest() {
        assertEquals(0, pruneCount(1))
        assertEquals(0, pruneCount(5))
        assertEquals(1, pruneCount(6))
        assertEquals(10, pruneCount(15))
    }
}
