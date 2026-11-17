package com.dilinkauto.desktop.log

import com.dilinkauto.protocol.LogLine

import java.io.Closeable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 桌面端日志：控制台回显 + 本地文件（默认 `%APPDATA%\DiLinkAuto\desktop.log`）。
 *
 * 分工：
 *  - 控制台**始终**输出，方便在 IDE / 命令行里联调；
 *  - 文件写入由 `config.json` 的 `log_enabled` 控制（与手机关闭 FileLog 的语义一致），
 *    关掉后 `desktop.log` 不再增长，但程序行为不变。
 *
 * 体积控制：**启动时**若日志已超过 [MAX_BYTES] 就轮转一次（`desktop.log` →
 * `desktop.log.1`，覆盖旧的 `.1`）。不做运行期轮转——一次会话写满 2MB 的场景不存在，
 * 而在写热路径上反复查文件大小只会拖慢每一条日志。
 *
 * 线程模型：会话日志可能来自 IO 协程、UI 线程与 Swing EDT，全部走 [lock]，
 * 保证整行原子写入（否则多线程并发 append 会把两行拼在一起）。
 */
class DesktopLog(
    private val file: File?,
    private val fileEnabled: Boolean = true,
    private val echoToConsole: Boolean = true,
    private val console: (String) -> Unit = ::println,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : Closeable {

    private val lock = Any()

    init {
        if (fileEnabled) rotateIfTooLarge(file)
    }

    fun info(tag: String, message: String) = write(LEVEL_INFO, tag, message)

    fun warn(tag: String, message: String) = write(LEVEL_WARN, tag, message)

    fun error(tag: String, message: String) = write(LEVEL_ERROR, tag, message)

    private fun write(level: String, tag: String, message: String) {
        val line = LogLine.bracketedTagLast(FORMATTER.format(clock()), level, tag, message)
        synchronized(lock) {
            if (echoToConsole) console(line)
            val target = file
            if (!fileEnabled || target == null) return
            runCatching {
                target.parentFile?.mkdirs()
                target.appendText(line + System.lineSeparator())
            }
        }
    }

    override fun close() {
        // 当前实现每条日志即刻落盘，没有需要 flush 的缓冲；保留 close() 是为了
        // 让调用方有一个明确的"停止写日志"语义（Phase 5 若改成缓冲写会用到）。
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"

        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** 超过 [MAX_BYTES] 时轮转：`desktop.log` → `desktop.log.1`（覆盖旧文件）。 */
        private fun rotateIfTooLarge(file: File?) {
            if (file == null || !file.isFile || file.length() <= MAX_BYTES) return
            val backup = File(file.parentFile, file.name + ".1")
            runCatching {
                backup.delete()
                file.renameTo(backup)
            }
        }
    }
}
