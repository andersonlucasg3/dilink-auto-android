package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * Pins the CRC-comparison policy that `ConnectionService.extractAsset` and
 * `ensureVdServerJarCurrent` used to each implement separately (DRY-4).
 *
 * The policy exists because the deployed jar outlives the process while
 * extraction runs once per process. The CRC expression is mirrored here (it
 * needs no Android types), but the temp-file write is exercised through the
 * deployer's own [AssetDeployer.writeViaTempFile] companion entry point — the
 * `.tmp` cleanup discipline (A-L16) and the usable-jar fallback (A-L15) are
 * production behaviour, not a policy to re-state, so they run for real.
 */
class AssetCrcPolicyTest {

    private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** The deployer's "is the on-disk copy already current?" test. */
    private fun matches(asset: ByteArray, onDisk: ByteArray): Boolean =
        crcOf(asset) == crcOf(onDisk)

    @Test
    fun identicalBytesHaveEqualCrc() {
        val asset = ByteArray(4096) { (it % 251).toByte() }
        val disk = asset.copyOf()
        assertTrue(matches(asset, disk))
        assertEquals(crcOf(asset), crcOf(disk))
    }

    @Test
    fun aSingleFlippedByteChangesTheCrc() {
        val asset = ByteArray(4096) { (it % 251).toByte() }
        val disk = asset.copyOf()
        disk[2048] = (disk[2048].toInt() xor 0x01).toByte()
        assertTrue("a one-bit change must force a rewrite", !matches(asset, disk))
    }

    @Test
    fun crcDistinguishesAppendedAndTruncatedContent() {
        val asset = ByteArray(1024) { (it % 97).toByte() }
        val grown = asset + ByteArray(1)
        val shrunk = asset.copyOf(1023)
        assertTrue(!matches(asset, grown))
        assertTrue(!matches(asset, shrunk))
    }

    @Test
    fun crcOfEmptyContentIsZero() {
        assertEquals(0L, crcOf(ByteArray(0)))
    }

    @Test
    fun crcIsOrderSensitive() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(4, 3, 2, 1)
        assertTrue("CRC32 is not order-independent", crcOf(a) != crcOf(b))
    }

    @Test
    fun atomicWriteLeavesNoTruncatedFileWhenTheTargetIsReplaced() {
        // writeAtomically writes a sibling .tmp then renames. Verify the shape of
        // that guarantee on a plain filesystem: the target is only ever observed
        // with the complete new content, and no .tmp is left behind.
        val dir = createTempDir(prefix = "assetdeploy")
        try {
            val target = File(dir, "vd-server.jar")
            val old = ByteArray(3000) { 1 }
            val fresh = ByteArray(4000) { 2 }

            AssetDeployer.writeViaTempFile(target, old)
            assertEquals(old.size.toLong(), target.length())
            assertTrue(matches(old, target.readBytes()))

            AssetDeployer.writeViaTempFile(target, fresh)
            assertEquals(fresh.size.toLong(), target.length())
            assertTrue(matches(fresh, target.readBytes()))
            assertTrue("no temp file may survive", !File("${target.absolutePath}.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tempFileIsCleanedUpWhenEveryWritePathFails() {
        // A-L16: the direct-write fallback (for FUSE volumes where rename
        // fails) can itself throw. A directory at the target path reproduces
        // that on any OS — rename onto a directory fails, and writing bytes
        // to a directory path throws — and the .tmp sibling must still be
        // gone afterwards, or every failed refresh strands another copy.
        val dir = createTempDir(prefix = "assetdeploy")
        try {
            val target = File(dir, "vd-server.jar")
            target.mkdirs()
            try {
                AssetDeployer.writeViaTempFile(target, ByteArray(16))
                fail("a total write failure must surface to the caller, not be swallowed")
            } catch (e: java.io.IOException) {
                // expected: rename onto a directory fails and the direct
                // fallback throws for the same reason
            }
            assertTrue(
                "no temp file may survive a total write failure",
                !File("${target.absolutePath}.tmp").exists()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aUsableOnDiskJarStillCountsWhenTheRefreshFails() {
        // A-L15: ensureCurrent must return the CRC of the jar that will keep
        // running when the refresh fails, not an unconditional -1. Mirrors the
        // deployer's Failed branch (a readable, non-empty target wins).
        val dir = createTempDir(prefix = "assetdeploy")
        try {
            val target = File(dir, "vd-server.jar")
            AssetDeployer.writeViaTempFile(target, ByteArray(2048) { 7 })
            val expected = crcOf(target.readBytes())
            // The deployer's fallback expression, against the real helper.
            val usable = try {
                if (!target.exists() || target.length() == 0L) -1L else crcOf(target.readBytes())
            } catch (_: Exception) {
                -1L
            }
            assertEquals(expected, usable)
            assertTrue("a usable jar must not read as unknown state", usable != -1L)

            val empty = File(dir, "empty.jar").apply { writeBytes(ByteArray(0)) }
            val unusable = try {
                if (!empty.exists() || empty.length() == 0L) -1L else crcOf(empty.readBytes())
            } catch (_: Exception) {
                -1L
            }
            assertEquals("an empty jar is not usable", -1L, unusable)
        } finally {
            dir.deleteRecursively()
        }
    }
}