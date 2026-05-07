package com.dilinkauto.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler for the car app.
 *
 * On crash: saves a crash report with stack trace and device info to
 * [filesDir]/crash-pending.log. The report is sent to the phone on the next
 * successful connection via carLogSend.
 */
object CarCrashHandler : Thread.UncaughtExceptionHandler {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    private const val CRASH_FILE = "crash-pending.log"

    /** Set by CarConnectionService so the handler can flush pending crashes on connect. */
    @Volatile var pendingCrashFile: File? = null
    @Volatile var logSink: ((String) -> Unit)? = null

    fun install(context: Context) {
        pendingCrashFile = File(context.filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val crashFile = pendingCrashFile
        val report = buildReport(thread, throwable)

        // Always write to file
        if (crashFile != null) {
            try {
                crashFile.writeText(report)
            } catch (_: Exception) {}
        }

        // Log to logcat as last resort
        try { Log.e("CarCrashHandler", report) } catch (_: Exception) {}

        // Attempt to send via TCP log sink if connected — brief wait for delivery
        val sink = logSink
        if (sink != null) {
            try {
                report.lines().forEach { sink(it) }
                Thread.sleep(1500) // allow time for TCP flush
            } catch (_: Exception) {}
        }

        // Hand off to original handler (or kill process)
        if (originalHandler != null && originalHandler !== this) {
            originalHandler.uncaughtException(thread, throwable)
        } else {
            // No original handler — kill the process ourselves
            try { Thread.sleep(500) } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    /**
     * Returns any pending crash report and moves the file to an archive
     * so it isn't re-sent on every connection.
     */
    fun consumePendingCrash(): String? {
        val f = pendingCrashFile ?: return null
        if (!f.exists()) return null
        return try {
            val content = f.readText()
            // Archive so we don't send it again
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            f.renameTo(File(f.parent, "crash-sent-$ts.log"))
            content
        } catch (_: Exception) { null }
    }

    fun buildDeviceInfo(context: Context): String {
        val sb = StringBuilder()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val dm = context.resources.displayMetrics

        sb.appendLine("── Device Info ──")
        sb.appendLine("model=${Build.MODEL} manufacturer=${Build.MANUFACTURER}")
        sb.appendLine("product=${Build.PRODUCT} device=${Build.DEVICE}")
        sb.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        sb.appendLine("display=${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi density=${dm.density}")
        sb.appendLine("cores=${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("abi=${Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"}")
        sb.appendLine()

        // Memory
        if (am != null) {
            sb.appendLine("── Memory ──")
            sb.appendLine("totalMem=${mi.totalMem} availMem=${mi.availMem} lowMemory=${mi.lowMemory}")
            sb.appendLine("heapMax=${Runtime.getRuntime().maxMemory()} heapTotal=${Runtime.getRuntime().totalMemory()} heapFree=${Runtime.getRuntime().freeMemory()}")
            sb.appendLine("nativeHeap=${Debug.getNativeHeapAllocatedSize()} nativeFree=${Debug.getNativeHeapFreeSize()}")
            val miPid = ActivityManager.MemoryInfo()
            am.getMemoryInfo(miPid)
            sb.appendLine("memThreshold=${mi.threshold}")

            // PSS info for this process
            try {
                sb.appendLine("pss=${Debug.getPss()}")
            } catch (_: Exception) {}
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        return buildString {
            appendLine("=== DiLink Auto Car Crash Report $ts ===")
            appendLine("thread=${thread.name}")
            appendLine()
            append(sw.toString())
            appendLine()
            appendLine("── Process State ──")
            appendLine("pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        }
    }
}
