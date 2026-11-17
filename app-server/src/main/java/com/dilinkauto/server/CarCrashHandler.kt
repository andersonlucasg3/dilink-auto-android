package com.dilinkauto.server

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler for the car app.
 *
 * On crash: saves a crash report (formatted by [CarCrashReport]) with stack
 * trace and device info to [filesDir]/crash-pending.log. The report is sent to
 * the phone on the next successful connection via carLogSend.
 */
object CarCrashHandler : Thread.UncaughtExceptionHandler {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    private const val CRASH_FILE = "crash-pending.log"

    /**
     * Newest archived crash reports to keep (audit S-L5).
     *
     * The archive is only diagnostics; without a cap, an app that crash-loops
     * fills the car's storage with `crash-sent-*.log` files that are never
     * read again.
     */
    private const val MAX_ARCHIVED_CRASHES = 5

    /**
     * Bound on the TCP log flush wait (audit S-L5).
     *
     * This runs on the thread that is about to die; a long sleep here delays
     * the crash dialog the user sees and the next process start. The sink is
     * best-effort — the report is already on disk either way.
     */
    private const val LOG_FLUSH_WAIT_MS = 300L

    /** Set by CarConnectionService so the handler can flush pending crashes on connect. */
    @Volatile var pendingCrashFile: File? = null
    @Volatile var logSink: ((String) -> Unit)? = null

    fun install(context: Context) {
        pendingCrashFile = File(context.filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val crashFile = pendingCrashFile
        val report = CarCrashReport.build(thread, throwable)

        // Always write to file
        if (crashFile != null) {
            try {
                crashFile.writeText(report)
            } catch (_: Exception) {}
        }

        // Log to logcat as last resort
        try { Log.e("CarCrashHandler", report) } catch (_: Exception) {}

        // Attempt to send via TCP log sink if connected — brief, *bounded* wait
        // for delivery (audit S-L5). Uninterruptible: we are the dying thread,
        // and the file write above already persisted the report.
        val sink = logSink
        if (sink != null) {
            try {
                report.lines().forEach { sink(it) }
                val deadline = System.currentTimeMillis() + LOG_FLUSH_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            } catch (_: Exception) {}
        }

        // Hand off to original handler (or kill process)
        if (originalHandler != null && originalHandler !== this) {
            originalHandler.uncaughtException(thread, throwable)
        } else {
            // No original handler — kill the process ourselves
            try { Thread.sleep(200) } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    /**
     * Returns any pending crash report and moves the file to an archive
     * so it isn't re-sent on every connection.
     *
     * Archived reports are pruned to the newest [MAX_ARCHIVED_CRASHES]
     * (audit S-L5).
     */
    fun consumePendingCrash(): String? {
        val f = pendingCrashFile ?: return null
        if (!f.exists()) return null
        return try {
            val content = f.readText()
            // Archive so we don't send it again
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            f.renameTo(File(f.parent, "crash-sent-$ts.log"))
            pruneArchivedCrashes()
            content
        } catch (_: Exception) { null }
    }

    /** Keep only the [MAX_ARCHIVED_CRASHES] newest `crash-sent-*.log` files. */
    private fun pruneArchivedCrashes() {
        val dir = pendingCrashFile?.parentFile ?: return
        val archived = try {
            dir.listFiles { _, name -> name.startsWith("crash-sent-") && name.endsWith(".log") }
        } catch (_: Exception) { null } ?: return
        if (archived.size <= MAX_ARCHIVED_CRASHES) return
        // Newest lastModified first; delete everything past the cap.
        archived.sortedByDescending { it.lastModified() }
            .drop(MAX_ARCHIVED_CRASHES)
            .forEach { try { it.delete() } catch (_: Exception) {} }
    }
}
