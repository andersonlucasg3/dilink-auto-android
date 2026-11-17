package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behaviour + concurrency contract of [IconHashGate] (audit A-M11).
 *
 * The gate is touched from two threads by design: `iconFor` runs on the IO
 * thread that assembles the app list, `reset` runs on the Main thread when a
 * session is torn down. The original `mutableMapOf` shared that way can lose
 * entries (a re-sent icon the car never receives) or, mid-resize, corrupt the
 * map structurally. The map is a ConcurrentHashMap now; these tests pin the
 * observable contract on both axes.
 */
class IconHashGateTest {

    @Test
    fun unchangedHashReturnsEmptyAndSkipsLoad() {
        val gate = IconHashGate()
        var loads = 0
        val first = gate.iconFor("pkg.a", "h1") { loads++; ByteArray(3) }
        val second = gate.iconFor("pkg.a", "h1") { loads++; ByteArray(3) }
        assertEquals("first send must carry the icon", 3, first.size)
        assertTrue("unchanged hash must gate the icon out", second.isEmpty())
        assertEquals("the load lambda must not run for an unchanged hash", 1, loads)
    }

    @Test
    fun emptyHashAlwaysLoads() {
        // A package whose change indicator is unavailable (hash == "") must
        // never be gated — an empty gate key would swallow every icon after
        // the first one.
        val gate = IconHashGate()
        var loads = 0
        gate.iconFor("pkg.a", "") { loads++; ByteArray(2) }
        gate.iconFor("pkg.a", "") { loads++; ByteArray(2) }
        assertEquals(2, loads)
    }

    @Test
    fun changedHashReloadsAndResetClearsEverything() {
        val gate = IconHashGate()
        gate.iconFor("pkg.a", "h1") { ByteArray(3) }
        assertTrue("changed hash must reload", gate.iconFor("pkg.a", "h2") { ByteArray(3) }.isNotEmpty())
        assertTrue("unchanged hash gates again", gate.iconFor("pkg.a", "h2") { ByteArray(3) }.isEmpty())

        gate.reset()
        assertFalse(
            "after reset every icon is unsent again",
            gate.iconFor("pkg.a", "h2") { ByteArray(3) }.isEmpty()
        )
    }

    @Test
    fun concurrentIconForDoesNotThrowAndKeepsPerThreadWrites() {
        val gate = IconHashGate()
        val threads = 8
        val iterations = 2000
        val start = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until iterations) {
                        // Shared keys from every thread, cycling through a few
                        // hashes so both the gate-out path and the load path
                        // run concurrently against the same entries.
                        val pkg = "pkg.${i % 8}"
                        val hash = "h-${i % 3}"
                        val bytes = gate.iconFor(pkg, hash) { ByteArray(4) }
                        if (bytes.isNotEmpty() && bytes.size != 4) error("bad load result")
                    }
                    // Per-thread key: the stamp must be immediately observable
                    // back to the same thread. A lost/corrupted entry shows up
                    // here as a non-empty second read.
                    gate.iconFor("pkg.own.$t", "own") { ByteArray(4) }
                    assertTrue(
                        "a write from this thread must be visible back to it",
                        gate.iconFor("pkg.own.$t", "own") { ByteArray(4) }.isEmpty()
                    )
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertTrue("concurrent access must not throw: $errors", errors.isEmpty())
    }

    @Test
    fun resetConcurrentWithIconForIsSafeAndObservable() {
        val gate = IconHashGate()
        val pkgs = (0 until 32).map { "pkg.$it" }
        pkgs.forEach { gate.iconFor(it, "h") { ByteArray(2) } }

        val start = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        val reader = Thread {
            try {
                start.await()
                repeat(20_000) { i ->
                    val bytes = gate.iconFor(pkgs[i % pkgs.size], "h") { ByteArray(2) }
                    if (bytes.isNotEmpty() && bytes.size != 2) error("bad load result")
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }.apply { isDaemon = true; start() }
        val resetter = Thread {
            start.await()
            repeat(2_000) { gate.reset() }
        }.apply { isDaemon = true; start() }
        start.countDown()
        reader.join(30_000)
        resetter.join(30_000)

        assertTrue("reset racing iconFor must not throw: $errors", errors.isEmpty())
        // Both threads are done. A reset racing iconFor can legitimately
        // interleave either way (the reader may add after the last reset), so
        // quiesce with one final single-threaded reset and assert it clears
        // every entry — the corruption/exceptions are what the race above pins.
        gate.reset()
        pkgs.forEach { pkg ->
            assertFalse(
                "a completed reset must clear every entry",
                gate.iconFor(pkg, "h") { ByteArray(2) }.isEmpty()
            )
        }
    }
}
