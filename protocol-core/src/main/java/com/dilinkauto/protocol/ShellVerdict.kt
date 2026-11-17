package com.dilinkauto.protocol

/**
 * 设备侧 shell 命令的成功判定（issue #23）。
 *
 * 两条 shell 通道（手机端 Shizuku 的 `execAndWait`、引擎的持久 sh）都只回传
 * 文本、不回传退出码，于是「命令失败」和「命令成功但没输出」在调用方看来一样，
 * 历史上 `cmd display power-reset 0 2>/dev/null` 的失败被彻底吞掉、还打了成功日志。
 *
 * 这里统一把命令包成 `cmd 2>&1; echo "__DILINK_RC=$?"`，再按两条硬性条件判定：
 *  1. 退出码 `rc == 0`；
 *  2. 输出里不含 `Unknown command`（2026-10-27 实测：toybox 的 `cmd` 对未知
 *     子命令打印 `Unknown command: power-foo` 并返回 **rc=255**；个别 ROM 可能
 *     返回 0，所以文本检查保留）。
 *
 * 手机端（[com.dilinkauto.client.service.DisplayRestoreSequence]）与引擎
 * （[com.dilinkauto.vdserver.DisplayPowerController]）共用同一份判定，避免两处
 * 漂移成「一边算成功、一边算失败」。
 */
object ShellVerdict {

    /** 包命令时追加的退出码标记；[detail] 会把它从失败原文里剔除。 */
    const val RC_MARKER = "__DILINK_RC="

    /** toybox `cmd` 对未知子命令的输出前缀。 */
    const val UNKNOWN_COMMAND = "Unknown command"

    /** 失败原文的截断长度：够定位，又不会把日志刷爆。 */
    private const val DETAIL_MAX_CHARS = 120

    /**
     * 把 [command] 包成带退出码标记的一行。
     *
     * `2>&1` 让 stderr 也进入同一条输出流（未知子命令的错误文案就在 stderr），
     * `;` 保证无论前一条命令成败都会执行 `echo`。
     */
    fun wrap(command: String): String = "$command 2>&1; echo \"$RC_MARKER\$?\""

    /** 判断一条 stdout/stderr 分离的结果是否成功。 */
    fun succeeded(stdout: String?, stderr: String?): Boolean {
        val text = listOfNotNull(stdout, stderr).joinToString("\n")
        if (text.isBlank()) return false
        if (text.contains(UNKNOWN_COMMAND)) return false
        val rc = text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith(RC_MARKER) }
            ?.removePrefix(RC_MARKER)
            ?.trim()
            ?.toIntOrNull()
            ?: return false
        return rc == 0
    }

    /** 判断一条已合并 stdout/stderr 的输出是否成功。 */
    fun succeeded(output: String?): Boolean = succeeded(output, null)

    /** 失败原文（去掉退出码标记、压成一行、截断），用于日志。 */
    fun detail(stdout: String?, stderr: String?): String {
        val text = listOfNotNull(stdout, stderr).joinToString("\n")
        val cleaned = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(RC_MARKER) }
            .joinToString(" ")
        return if (cleaned.isEmpty()) "no output" else cleaned.take(DETAIL_MAX_CHARS)
    }

    /** 失败原文（已合并 stdout/stderr）。 */
    fun detail(output: String?): String = detail(output, null)
}
