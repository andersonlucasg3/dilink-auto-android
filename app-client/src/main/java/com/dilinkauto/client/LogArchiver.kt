package com.dilinkauto.client

import android.util.Log
import com.dilinkauto.protocol.VdDeploy
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rotation, pruning and packaging of the client's log files.
 *
 * Extracted from [FileLog] (docs/audit-srp-dry.md SRP-6). Writing a line and
 * deciding what to do with yesterday's file are unrelated responsibilities that
 * had ended up in one object; the archiver is pure filesystem work with no
 * writer-thread or queue state, so it can be reasoned about on its own.
 *
 * Everything here is best-effort: a failure to rotate or zip must never take
 * down the app, so each entry point swallows and logs.
 */
internal class LogArchiver(
    private val logDir: File,
    /** Prefix identifying archives of this app's own logs. */
    private val archivePrefix: String = "client-",
    /**
     * Number of archives to keep. Total on disk = keep + the live file.
     *
     * 9 → 30（issue #72）：长时间导航中途反复断线重连，每次服务重启都会轮转一份
     * 归档，9 份在几小时内就会被删光，早期现场反而留不下来。
     */
    private val keepArchives: Int = 30
) {

    /**
     * Moves the current log aside and prunes old archives.
     *
     * @param currentFile the live log; renamed to `<prefix><timestamp>.log`.
     * @return the archive that was created, or null when there was nothing to
     *   archive or the operation failed.
     */
    fun rotate(currentFile: File): File? = try {
        if (currentFile.exists() && currentFile.length() > 0) {
            val archive = File(
                logDir,
                archivePrefix + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            )
            if (!currentFile.renameTo(archive)) {
                Log.e(TAG, "Failed to rename ${currentFile.name} to ${archive.name}")
                null
            } else {
                prune()
                archive
            }
        } else {
            // Nothing worth keeping (empty or absent) — still prune, so a long
            // silent-stdout run cannot accumulate archives indefinitely.
            prune()
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to rotate log file: ${e.message}")
        null
    }

    /** Deletes all but the newest [keepArchives] archives. */
    private fun prune() {
        val archived = logDir.listFiles { f ->
            f.name.startsWith(archivePrefix) && f.name.endsWith(".log")
        }?.sortedByDescending { it.name } ?: return
        archived.drop(keepArchives).forEach { it.delete() }
    }

    /**
     * Bundles every log in [logDir] plus, if present, the vd-server log written
     * by the shell process into a single zip.
     *
     * @return the archive, or null on failure.
     */
    fun zip(extraFiles: List<File> = emptyList()): File? = try {
        val zipFile = File(logDir, ZIP_NAME)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val logFiles = logDir.listFiles { f -> f.name.endsWith(".log") } ?: emptyArray()
            for (file in logFiles) {
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            // The vd-server log used to live in the shell's own dir, outside
            // logDir. It now resolves to VdDeploy.LOG_PATH (audit A-L23) —
            // which is inside logDir, so the scan above already staged it.
            // Adding it again as an extra would throw ZipException
            // ("duplicate entry") and fail the whole share; track what is
            // staged and skip duplicates instead.
            val staged = logFiles.mapTo(mutableSetOf()) { it.absolutePath }
            for (file in extraFiles) {
                if (!file.exists()) continue
                if (!staged.add(file.absolutePath)) continue
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        zipFile
    } catch (e: Exception) {
        Log.e(TAG, "Failed to zip logs: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "LogArchiver"
        const val ZIP_NAME = "dilinkauto-logs.zip"

        /**
         * Where the vd-server shell process writes its log — the same path the
         * deploy command redirects to (audit A-L23: this used to point at
         * /data/local/tmp, a file the engine never writes on this build, so
         * every archived zip contained a stale/absent log).
         */
        val VD_SERVER_LOG = File(VdDeploy.LOG_PATH)
    }
}