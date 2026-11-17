package com.dilinkauto.desktop.deploy

/**
 * ab 进程的执行抽象。抽出来是为了让 [AdbDeployer] 的命令序列可以脱离真实
 * `adb.exe` 单测（CI 上不会装 adb，也不该起真机进程）。
 *
 * 单独成文件（docs/audit-srp-dry.md SRP-8）：接口与它唯一的实现
 * [ProcessAdbRunner] 是两份独立的关注点，实现里还有进程持有与流排空的逻辑，
 * 不该和部署命令序列挤在一个文件里。
 */
interface AdbRunner {

    /**
     * 跑一条 adb 命令。
     *
     * @param waitForExit true：等进程结束，返回值表示"退出码为 0"；超时会强杀并返回 false。
     *   false：启动即返回，进程保持附着不关闭 —— 这正是 VD server 需要的行为
     *   （`exec app_process` 接管 adb shell 流，流一关引擎就被回收）。
     * @param onOutput 进程输出行（stdout+stderr 已合并），用于写进 desktop.log。
     */
    fun run(args: List<String>, waitForExit: Boolean, timeoutMs: Long, onOutput: (String) -> Unit): Boolean

    /** 杀掉所有还挂着的长驻进程（会话结束 / 重启 / 退出时调用）。 */
    fun killAll()
}