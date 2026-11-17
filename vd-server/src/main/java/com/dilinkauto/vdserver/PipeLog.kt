package com.dilinkauto.vdserver

import java.io.OutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Logging and shell helpers shared by the VD server's pipeline collaborators
 * ([PipelineServer], [GlPipeline], [TouchInjector], [DisplayPowerController]).
 *
 * The VD server runs as shell via `app_process` and has no android.util.Log —
 * everything goes to stdout/stderr, which app_process redirects to the
 * vd-server.log file the car reads. Centralizing the format here keeps the
 * `[Pipeline]` prefix consistent and removes the per-class `log`/`err`
 * duplicates that were copy-pasted across the four files.
 *
 * Convention (audit R3-DRY-22③): regular log lines always call [log] directly —
 * no per-class alias wrappers. The injected `logErr: (String) -> Unit` parameter
 * that [TouchInjector]/[DisplayPowerController] take is *not* such an alias: it
 * is a DI seam so [PipelineServer] owns error routing.
 */
internal object PipeLog {
    /** 与 `FileLog` / `CarLogWriter` 的毫秒时间格式保持一致。 */
    private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(msg: String) { println("[Pipeline] $msg"); System.out.flush() }
    fun err(msg: String) { System.err.println("[Pipeline] $msg"); System.err.flush() }

    /**
     * issue #64：带时钟的诊断行。
     *
     * `vd-server.log` 由 shell `>>` 追加，本身没有时间戳；窗口/触控取证行必须自带
     * `HH:mm:ss.SSS`，否则无法和车机端 `STALL` 行（`CarLogWriter` 已带时间）对齐
     * 「卡顿发生在哪一刻」。只给新增诊断行使用，不改既有行的格式。
     */
    fun logClock(msg: String) {
        log("${LocalTime.now().format(CLOCK_FORMAT)} $msg")
    }
}

/**
 * Shell command execution against the VD server's persistent `sh` process.
 *
 * The persistent shell is kept alive for the whole server lifetime so we don't
 * fork a new `sh` per `input`/`settings`/`am` call. [execShell] writes a line
 * to the shell's stdin; [execShellOutput] spawns a one-shot `sh -c` when we
 * need to capture stdout. Both swallow exceptions — the VD server must not die
 * because a settings put failed.
 */
internal object ShellExec {
    /** 默认命令执行超时：3 秒（避免 dumpsys / am 等命令死锁拖死调用线程） */
    const val DEFAULT_EXEC_TIMEOUT_MS = 3000L

    /** 输出摘要的截断长度：避免 dumpsys 等大输出把 vd-server.log 刷爆 */
    private const val OUTPUT_SUMMARY_CHARS = 400

    /** Write `cmd\n` to the persistent shell's stdin and flush. */
    fun execShell(shellInput: OutputStream?, cmd: String) {
        PipeLog.log("sh> $cmd")
        try {
            // S-L3: this stream is shared by TouchInjector / DisplayPowerController /
            // CarCommandRouter from three different threads. Without the lock a write
            // longer than PIPE_BUF (4096) can interleave with another thread's command
            // and corrupt both lines — the shell then executes a mash-up of two commands.
            // flush is inside the lock so readers never see a partial line.
            shellInput?.let { out ->
                synchronized(out) {
                    out.write("$cmd\n".toByteArray())
                    out.flush()
                }
            }
        }
        catch (e: Exception) { PipeLog.err("sh> write failed: ${e.message} (cmd=$cmd)") }
    }

    /**
     * Run `sh -c cmd`, capture stdout + stderr, block until exit with timeout. Returns null on any failure.
     *
     * 用 ProcessBuilder + redirectErrorStream 合并 stderr：am start 等命令的报错
     * （"Error: Activity ... does not exist"）不再丢失，同时避免 stderr 管道无人
     * 读取被写满后卡死调用线程。每次执行都记录命令、退出码与输出摘要。
     * 支持有界超时（默认 3 秒），超时强制 destroyForcibly 并返回 null。
     */
    fun execShellOutput(
        cmd: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
        processStarter: (String) -> Process = { c -> ProcessBuilder("sh", "-c", c).redirectErrorStream(true).start() }
    ): String? = try {
        val p = processStarter(cmd)
        val outBuilder = StringBuilder()
        val readerThread = daemonThread("ShellExecReader", start = true) {
            try {
                val buf = CharArray(1024)
                p.inputStream.bufferedReader().use { reader ->
                    var n = reader.read(buf)
                    while (n != -1) {
                        outBuilder.append(buf, 0, n)
                        n = reader.read(buf)
                    }
                }
            } catch (_: Exception) {}
        }
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) {
            p.destroyForcibly()
            readerThread.interrupt()
            PipeLog.err("sh# $cmd -> timed out after ${timeoutMs}ms")
            null
        } else {
            readerThread.join(500)
            val o = outBuilder.toString()
            val summary = o.trim().replace(Regex("\\s+"), " ")
            PipeLog.log("sh# $cmd -> exit=${p.exitValue()} out='${summary.take(OUTPUT_SUMMARY_CHARS)}'")
            o
        }
    } catch (e: Exception) {
        PipeLog.err("sh# $cmd -> failed: ${e.message}")
        null
    }

    /**
     * 给持久 sh 进程挂 stdout/stderr 排水线程。
     *
     * 持久 sh 的 stdout/stderr 是管道：无人读取时缓冲写满（约 64KB）会让 sh 永久
     * 阻塞在 write 上，之后所有命令（am/input/settings）静默失效。排水线程把每行
     * 输出写进 vd-server.log，既防阻塞，又能看到命令的真实回显与报错。
     *
     * @param onLine 每读到一行输出回调一次（[PersistentShell] 用它驱动
     *   [ShellSyncPoint] 的同步屏障 —— teardown 时"等最后几条恢复命令执行完"
     *   依赖 stdout 的回显顺序）；日志照记。
     */
    fun startOutputDrain(p: Process, onLine: ((String) -> Unit)? = null) {
        for ((name, stream) in listOf("out" to p.inputStream, "err" to p.errorStream)) {
            daemonThread("ShellDrain-$name", start = true) {
                try {
                    stream.bufferedReader().forEachLine { line ->
                        PipeLog.log("sh<$name| $line")
                        onLine?.invoke(line)
                    }
                }
                catch (_: Exception) {}
            }
        }
    }
}
