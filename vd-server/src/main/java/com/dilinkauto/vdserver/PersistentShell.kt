package com.dilinkauto.vdserver

import java.io.OutputStream

/**
 * The long-lived `sh` process every VD-server shell command goes through
 * (audit R3-SRP-10).
 *
 * Started in [PipelineServer.run] before the encoder is set up, torn down last
 * in `cleanup()` — the teardown ordering there ("restore screen BEFORE killing
 * shell") depends on this process staying alive through the panel restore.
 *
 * A null [input] means the shell never started; [exec] then becomes a silent
 * no-op, which is the behaviour every caller already tolerates.
 *
 * 2026-10-10（MI 9 实测）：`exec` 是"写完即返回"，而 `cleanup()` 末尾写完恢复
 * 命令后马上 `close()`（destroy → SIGTERM）—— 最后几条命令会被截断（实测丢了
 * `settings put system screen_off_timeout 60000`）。因此 [close] 之前必须走一次
 * [awaitIdle] 屏障：`echo` 一个唯一标记并等排水线程回显，FIFO 保证此前命令
 * 都已执行完。详见 [ShellSyncPoint]。
 */
internal class PersistentShell(
    syncTimeoutMs: Long = ShellSyncPoint.DEFAULT_TIMEOUT_MS,
) {

    private var process: Process? = null

    /** stdin of the persistent shell; null until [start] succeeds. */
    var input: OutputStream? = null
        private set

    private val syncPoint = ShellSyncPoint(syncTimeoutMs)

    fun start() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh"))
            process = p
            input = p.outputStream
            // 给持久 sh 挂 stdout/stderr 排水线程：防止管道无人读被写满后 sh 卡死（后续命令全部静默失效）
            ShellExec.startOutputDrain(p) { line -> syncPoint.onLine(line) }
            PipeLog.log("Shell: persistent sh started")
        } catch (e: Exception) {
            PipeLog.err("Shell: ${e.message}")
        }
    }

    /** Write `cmd\n` to the shell's stdin. Never throws. */
    fun exec(cmd: String) = ShellExec.execShell(input, cmd)

    /** One-shot `sh -c`, captures output with timeout. Never throws. */
    fun execOutput(cmd: String, timeoutMs: Long = ShellExec.DEFAULT_EXEC_TIMEOUT_MS): String? =
        ShellExec.execShellOutput(cmd, timeoutMs)

    /**
     * 阻塞直到**此前写入的所有命令都已执行完**（见类头与 [ShellSyncPoint]）。
     *
     * 语义：`echo <token>` 的回显落在 stdout 尾部 ⇒ 管道 FIFO ⇒ 先写入的命令
     * 都已执行。返回 false = 超时（sh 已死等），调用方继续收尾即可 —— 这是
     * best-effort 的"别让最后几条恢复命令陪葬"，失败不会让 cleanup 卡死。
     */
    fun awaitIdle(): Boolean {
        if (input == null) return true
        val token = syncPoint.arm()
        exec("echo $token")
        val ok = syncPoint.await()
        if (!ok) {
            PipeLog.err("Shell: awaitIdle timed out (${ShellSyncPoint.DEFAULT_TIMEOUT_MS}ms) — teardown commands may not have executed")
        }
        return ok
    }

    /** Close stdin first, then destroy the process. Never throws. */
    fun close() {
        process?.let {
            try { input?.close() } catch (_: Exception) {}
            it.destroy()
        }
    }
}
