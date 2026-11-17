package com.dilinkauto.client

import android.util.Log
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.AsyncLogQueue
import com.dilinkauto.protocol.VdDeploy
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based logger that bypasses Android logcat filtering.
 * Writes to /sdcard/DiLinkAuto/client.log and also calls android.util.Log.
 *
 * On rotate(), the current log is renamed with a timestamp and a fresh log starts.
 * Old session logs accumulate in the folder (client-YYYYMMDD-HHmmss.log).
 * Thread-safe: uses an [AsyncLogQueue] drained by a single writer thread.
 */
object FileLog {

    /** Max queued lines before the oldest policy drops the newest (audit A-05). */
    private const val LOG_QUEUE_CAPACITY = 2048

    /** Toggled from Settings. When false, no file writes or logcat output. */
    @Volatile var enabled = true

    /**
     * Bounded queue (audit A-05): the DATA channel dispatch is asynchronous and
     * the frame payload cap lets a peer enqueue huge lines; an UNLIMITED queue
     * turned that into unbounded heap growth on a remote trigger. Overflow
     * drops the newest line — for a debug log that is always the right trade.
     */
    private val queue = AsyncLogQueue<String>(capacity = LOG_QUEUE_CAPACITY)
    @Volatile private var writer: FileWriter? = null
    // SimpleDateFormat is NOT thread-safe — the writer thread formats all
    // timestamps, so a single shared instance is safe here. Callers only ever
    // queue raw message strings (no formatting on the calling thread).
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    // Same literal path the vd-server engine writes to (VdDeploy.DIR_PATH —
    // audit A-L23). Environment.getExternalStorageDirectory() resolved to a
    // *different* directory on this ROM (the code's own comments recorded the
    // divergence), so log sharing showed client logs from one folder while
    // the engine's log lived in another. One constant, one place.
    private val logDir = File(VdDeploy.DIR_PATH)
    private val logFile = File(logDir, "client.log")
    private val archiver = LogArchiver(logDir)

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
            // Single consumer. runBlocking parks this thread on the queue's
            // channel receive — no polling sleep, wakes as soon as a line arrives.
            runBlocking {
                queue.consume { line ->
                    try {
                        // Format on the writer thread only — SimpleDateFormat is
                        // not thread-safe, and this is the single consumer.
                        val ts = dateFormat.format(Date())
                        val parts = line.split("␞", limit = 3)
                        val (level, tag, msg) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("I", "FileLog", line)
                        writer?.write(com.dilinkauto.protocol.LogLine.bracketedTagFirst(ts, level, tag, msg))
                        writer?.write("\n")
                        writer?.flush()
                    } catch (_: Exception) {}
                }
            }
        }, "FileLog").apply { isDaemon = true; start() }
    }

    /**
     * Rotate: rename current log with timestamp, start fresh.
     * Keeps at most 30 log files (29 archived + current). Oldest are deleted.
     */
    /**
     * Read enabled state from SharedPreferences. Call on service start.
     * Default: ON for debug/pre-release builds, OFF for release builds.
     * Once user explicitly toggles, that choice persists regardless of build type.
     */
    fun loadEnabled(prefs: android.content.SharedPreferences) {
        enabled = isEnabledIn(prefs)
    }

    /**
     * 「开关是否真的在落盘」的唯一判定入口（issue #72）。
     *
     * 设置页此前直接读 `getBoolean(LOG_ENABLED, true)`：release 构建下用户没设过
     * 开关时 UI 显示「开」，而 [loadEnabled] 按 [BuildConfig.DEBUG] 算出来是「关」，
     * 于是开关在撒谎。UI 与运行时现在都走这一个入口，规则只有一份。
     *
     * 注意：默认仍然关闭，不为长时间导航的常态 I/O 增加发烫风险。
     */
    fun isEnabledIn(prefs: android.content.SharedPreferences): Boolean =
        resolveLogEnabled(
            userSet = prefs.getBoolean(AppPrefs.LOG_ENABLED_USER_SET, false),
            userValue = prefs.getBoolean(AppPrefs.LOG_ENABLED, false),
            debugBuild = com.dilinkauto.client.BuildConfig.DEBUG
        )

    fun rotate() {
        queue.clear()
        writer?.close()
        archiver.rotate(logFile)
        try {
            writer = FileWriter(logFile, false)
            writer?.write("=== DiLink Auto Client log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to reopen log file after rotate: ${e.message}")
        }
    }

    fun i(tag: String, msg: String) { if (enabled) { Log.i(tag, msg); write("I", tag, msg) } }
    fun d(tag: String, msg: String) { if (enabled) { Log.d(tag, msg); write("D", tag, msg) } }
    fun w(tag: String, msg: String) { if (enabled) { Log.w(tag, msg); write("W", tag, msg) } }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        write("E", tag, "$msg${t?.let { " | ${it.message}" } ?: ""}")
    }

    fun zipLogs(): File? = archiver.zip(extraFiles = listOf(LogArchiver.VD_SERVER_LOG))

    fun logDirectory(): File = logDir

    private fun write(level: String, tag: String, msg: String) {
        // No timestamp formatting on the calling thread — queue the raw pieces and
        // let the single writer thread format with the shared SimpleDateFormat.
        queue.offer("$level␞$tag␞$msg")
    }
}

/**
 * 诊断日志开关的判定规则（issue #72）。
 *
 * 抽成纯函数是为了能被 JVM 单测直接覆盖：`FileLog` 是持有 writer 线程的对象，
 * 在单测里构造它会真的开文件。
 *
 * @param userSet 用户是否在设置页显式切换过开关
 * @param userValue 用户显式选择的值
 * @param debugBuild 当前构建是否为 debug/pre-release
 * @return 最终是否落盘
 */
internal fun resolveLogEnabled(userSet: Boolean, userValue: Boolean, debugBuild: Boolean): Boolean =
    if (userSet) userValue else debugBuild
