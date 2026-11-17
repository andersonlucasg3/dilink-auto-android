package com.dilinkauto.client.service

import android.content.res.AssetManager
import com.dilinkauto.client.FileLog
import java.io.File
import java.util.zip.CRC32

/**
 * Unpacks bundled assets to disk, skipping the write when the on-disk copy
 * already matches.
 *
 * Extracted from [ConnectionService]: `extractAsset` and
 * `ensureVdServerJarCurrent` each independently implemented the same
 * read-asset → CRC32 → compare-with-target → `writeAtomically` sequence, so a
 * fix to one silently skipped the other (docs/audit-srp-dry.md DRY-4).
 *
 * The CRC comparison is the reason this exists at all rather than a plain copy.
 * The deployed `vd-server.jar` outlives the process while [extract] runs only
 * once per process, so without this check `app_process` can silently load a
 * stale engine — the app still runs and still logs, it just runs old code.
 */
internal class AssetDeployer(private val source: AssetSource) {

    /**
     * CRC of the on-disk file if the write happened or the file was already
     * current; `null` if the asset could not be read or written.
     */
    sealed class Result {
        /** Target now matches the asset. [crc] is its CRC. */
        data class Current(val crc: Long) : Result()

        /** Target was written from a newer/different asset. [crc] is its CRC. */
        data class Written(val crc: Long, val bytes: Int) : Result()

        /** The asset could not be read or written. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Ensures [target] holds the current contents of [assetName], writing only
     * when the CRCs differ.
     */
    fun extract(assetName: String, target: File): Result {
        val assetBytes = try {
            source.read(assetName)
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to read asset $assetName: ${e.message}")
            return Result.Failed("read: ${e.message}")
        }

        val assetCrc = crc32(assetBytes)

        // mkdirs must precede the existence check: the jar lands in
        // /sdcard/DiLinkAuto, which does not exist on a fresh install.
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val fileCrc = try {
                crc32(target.readBytes())
            } catch (_: Exception) {
                null // unreadable target — fall through and overwrite
            }
            if (fileCrc == assetCrc) {
                FileLog.i(TAG, "$assetName up-to-date (crc=$assetCrc)")
                return Result.Current(assetCrc)
            }
        }

        return try {
            writeAtomically(target, assetBytes)
            FileLog.i(
                TAG,
                "$assetName deployed to ${target.absolutePath} (${assetBytes.size} bytes, crc=$assetCrc)"
            )
            Result.Written(assetCrc, assetBytes.size)
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to extract $assetName: ${e.message}")
            Result.Failed("write: ${e.message}")
        }
    }

    /**
     * Ensures [target] holds the current contents of [assetName] and reports
     * which jar will run.
     *
     * @return the CRC of the jar now in place: verified current, freshly
     *   written, or the last usable on-disk copy when the refresh failed
     *   (that copy still runs); `-1` when there is no usable file and the
     *   caller should decide whether to abort.
     */
    fun ensureCurrent(assetName: String, target: File): Long =
        when (val r = extract(assetName, target)) {
            is Result.Current -> r.crc
            is Result.Written -> {
                FileLog.i(TAG, "VD jar refreshed: ${target.absolutePath} (${r.bytes} bytes, crc=${r.crc})")
                r.crc
            }
            is Result.Failed -> {
                FileLog.e(TAG, "VD jar refresh failed: ${r.reason}")
                // A usable jar already on disk is still usable; otherwise the
                // caller has to decide whether to abort. The CRC of that file
                // is returned so the caller can log/report exactly which jar
                // will run (audit A-L15: both branches used to return -1L
                // unconditionally, making the KDoc's "usable value" contract
                // unreachable and every caller's fallback branch dead).
                usableExistingCrc(target)
            }
        }

    /**
     * CRC of a non-empty readable [target], or -1 when there is no usable
     * file (missing, empty, unreadable). Read failure means the state is
     * unknown, which is what the -1 sentinel tells the caller.
     */
    private fun usableExistingCrc(target: File): Long = try {
        if (!target.exists() || target.length() == 0L) -1L else crc32(target.readBytes())
    } catch (_: Exception) {
        -1L
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /**
     * Write via a temp file + rename so a crash mid-write cannot leave a
     * truncated jar that still passes an existence check.
     *
     * Falls back to a direct write when rename fails: on FUSE-backed storage
     * (some emulated external volumes) rename can fail, and a complete-but-
     * unrenamed file still beats losing the asset entirely.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) = writeViaTempFile(target, bytes)

    companion object {
        private const val TAG = "AssetDeployer"

        /**
         * The [writeAtomically] body, in the companion so the cleanup
         * discipline is unit-testable without an Android [AssetManager].
         *
         * The `finally` is load-bearing (audit A-L16): the fallback direct
         * write can throw too, and without the delete every failed refresh
         * stranded another `<target>.tmp` sibling on shared storage.
         */
        internal fun writeViaTempFile(target: File, bytes: ByteArray) {
            val tmp = File("${target.absolutePath}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                }
            } finally {
                // No-op when the rename already consumed the tmp (it no
                // longer exists) — delete() just returns false.
                tmp.delete()
            }
        }
    }
}

/**
 * Asset byte-source seam. The production implementation wraps the Android
 * `AssetManager` (which is `final` and therefore cannot be subclassed as a test
 * double); tests inject a fake so [AssetDeployer.extract] / [ensureCurrent] —
 * the on-disk `vd-server.jar` CRC-freshness gate — become unit-testable.
 */
internal interface AssetSource {
    @Throws(Exception::class)
    fun read(assetName: String): ByteArray
}

/** Production [AssetSource] over the Android [AssetManager]. */
internal class AssetManagerAssetSource(private val assets: AssetManager) : AssetSource {
    override fun read(assetName: String): ByteArray =
        assets.open(assetName).use { it.readBytes() }
}