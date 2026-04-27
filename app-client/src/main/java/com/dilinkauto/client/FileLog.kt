package com.dilinkauto.client

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * File-based logger that bypasses Android logcat filtering.
 * Writes to /sdcard/DiLinkAuto/client.log and also calls android.util.Log.
 *
 * On rotate(), the current log is renamed with a timestamp and a fresh log starts.
 * Old session logs accumulate in the folder (client-YYYYMMDD-HHmmss.log).
 * Thread-safe: uses a lock-free queue drained by a single writer thread.
 */
object FileLog {

    private val queue = ConcurrentLinkedQueue<String>()
    @Volatile private var writer: FileWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logDir = File(Environment.getExternalStorageDirectory(), "DiLinkAuto")
    private val logFile = File(logDir, "client.log")

    init {
        try {
            logDir.mkdirs()
            writer = FileWriter(logFile, true) // append on process start
            writer?.write("=== DiLink Auto Client log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to open log file: ${e.message}")
        }

        Thread({
            while (true) {
                try {
                    val line = queue.poll()
                    if (line != null) {
                        writer?.write(line)
                        writer?.write("\n")
                        writer?.flush()
                    } else {
                        Thread.sleep(200) // reduced poll rate for low-end devices
                    }
                } catch (_: Exception) {}
            }
        }, "FileLog").apply { isDaemon = true; start() }
    }

    /**
     * Rotate: rename current log with timestamp, start fresh.
     * Keeps at most 10 log files (9 archived + current). Oldest are deleted.
     */
    fun rotate() {
        queue.clear()
        try {
            writer?.close()
            if (logFile.exists() && logFile.length() > 0) {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val archiveFile = File(logDir, "client-$ts.log")
                logFile.renameTo(archiveFile)
            }
            // Prune: keep only 9 archived logs (+ the new current = 10 total)
            val archived = logDir.listFiles { f -> f.name.startsWith("client-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
            archived?.drop(9)?.forEach { it.delete() }

            writer = FileWriter(logFile, false)
            writer?.write("=== DiLink Auto Client log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to rotate log file: ${e.message}")
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        write("E", tag, "$msg${t?.let { " | ${it.message}" } ?: ""}")
    }

    private fun write(level: String, tag: String, msg: String) {
        val ts = dateFormat.format(Date())
        queue.add("[$ts][$level][$tag] $msg")
    }
}
