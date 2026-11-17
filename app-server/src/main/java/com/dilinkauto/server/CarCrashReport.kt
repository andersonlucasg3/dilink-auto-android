package com.dilinkauto.server

import android.app.ActivityManager
import android.content.Context
import com.dilinkauto.protocol.DeviceInfo
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash report and device-info assembly (audit R3-SRP-19), split out of
 * [CarCrashHandler]. The handler keeps delivery (file / logcat / TCP sink) and
 * the original-handler hand-off; this object only formats text.
 */
internal object CarCrashReport {

    /** Full crash report: timestamp, thread, stack trace and process state. */
    fun build(thread: Thread, throwable: Throwable): String {
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

    /**
     * Device info for the car log / crash header.
     *
     * Device Info block is shared with the phone-side ConnectionService via
     * [com.dilinkauto.protocol.DeviceInfo]; the car report extends it with the
     * Memory section and the memThreshold line.
     */
    fun deviceInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine(DeviceInfo.buildDeviceInfoBlock(context, includeMemory = true))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            sb.appendLine("memThreshold=${ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.threshold}")
            sb.appendLine()
        }

        return sb.toString()
    }
}
