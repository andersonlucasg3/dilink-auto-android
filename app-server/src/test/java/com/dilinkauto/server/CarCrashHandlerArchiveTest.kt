package com.dilinkauto.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the S-L5 crash-archive prune: `consumePendingCrash` moves
 * `crash-pending.log` aside so it is not re-sent, and keeps only the newest
 * `MAX_ARCHIVED_CRASHES` `crash-sent-*.log` files.
 *
 * Runs against a [TemporaryFolder], so it needs no Android framework at all —
 * `consumePendingCrash` only touches the filesystem (the logcat/sink delivery
 * lives in `uncaughtException`, which is deliberately not triggered here).
 */
class CarCrashHandlerArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pending(): File = File(tmp.root, "crash-pending.log")

    private fun archived(): List<File> =
        (tmp.root.listFiles { _, name -> name.startsWith("crash-sent-") && name.endsWith(".log") }
            ?: emptyArray()).sortedBy { it.name }

    /** Age the archives so "newest by lastModified" is deterministic. */
    private fun age(files: List<File>) {
        files.forEachIndexed { i, f ->
            f.setLastModified(System.currentTimeMillis() - i * 60_000L)
        }
    }

    private fun withPendingFile(body: (File) -> Unit) {
        val previous = CarCrashHandler.pendingCrashFile
        CarCrashHandler.pendingCrashFile = pending()
        try {
            body(pending())
        } finally {
            CarCrashHandler.pendingCrashFile = previous
        }
    }

    @Test
    fun noPendingFile_yieldsNull() {
        withPendingFile { }
        assertNull(CarCrashHandler.consumePendingCrash())
    }

    @Test
    fun pendingCrash_isArchivedAndConsumedOnce() {
        withPendingFile { f ->
            f.writeText("boom")
            val first = CarCrashHandler.consumePendingCrash()
            assertEquals("boom", first)
            assertFalse("pending file must be moved aside", f.exists())
            assertEquals(1, archived().size)

            // Second call must not re-deliver the same report.
            assertNull(CarCrashHandler.consumePendingCrash())
            assertEquals(1, archived().size)
        }
    }

    @Test
    fun archiveIsPrunedToNewestFive() {
        withPendingFile { f ->
            // 8 pre-existing archives; `age` makes index 0 the newest, so
            // old-1 is the newest seed and old-8 the oldest.
            val seeds = (1..8).map { i ->
                File(tmp.root, "crash-sent-20260101-00000$i.log").also { it.writeText("old-$i") }
            }
            age(seeds)
            f.writeText("fresh")

            assertNotNull(CarCrashHandler.consumePendingCrash())

            val survivors = archived()
            assertEquals("only the newest 5 survive", 5, survivors.size)
            // The just-archived report is the newest and must be kept.
            assertTrue(survivors.any { it.readText() == "fresh" })
            // The four oldest seeds are gone; the four newest seeds remain.
            assertTrue(survivors.any { it.readText() == "old-1" })
            assertTrue(survivors.any { it.readText() == "old-4" })
            assertFalse(survivors.any { it.readText() == "old-5" })
            assertFalse(survivors.any { it.readText() == "old-8" })
        }
    }

    @Test
    fun atCap_nothingIsDeleted() {
        withPendingFile { f ->
            // 4 seeds + the freshly archived report = exactly the cap.
            val seeds = (1..4).map { i ->
                File(tmp.root, "crash-sent-20260101-00000$i.log").also { it.writeText("old-$i") }
            }
            age(seeds)
            f.writeText("fresh")

            assertNotNull(CarCrashHandler.consumePendingCrash())
            assertEquals(5, archived().size)
            assertTrue(archived().any { it.readText() == "old-1" })
            assertTrue(archived().any { it.readText() == "fresh" })
        }
    }

    @Test
    fun repeatedCrashBursts_stayBounded() {
        withPendingFile { f ->
            repeat(20) { i ->
                f.writeText("crash-$i")
                assertNotNull(CarCrashHandler.consumePendingCrash())
                assertTrue(
                    "archive grew past the cap at crash $i (${archived().size} files)",
                    archived().size <= 5
                )
            }
            // Something survived, and the archive never ran away.
            assertTrue(archived().isNotEmpty())
            assertTrue(archived().size <= 5)
        }
    }
}
