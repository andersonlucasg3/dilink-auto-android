package com.dilinkauto.vdserver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 「已写入常驻 sh 的命令都执行完了」的汇合点（2026-10-10 新增，MI 9 实测）。
 *
 * ── 为什么需要 ──
 * [ShellExec.execShell] 把 `cmd\n` 写进常驻 sh 的 stdin 就返回（有意为之：会话中
 * 每条 `am`/`input`/`settings` 都等一次往返太慢）。但 `cleanup()` 的末尾不同：
 * 恢复命令写完之后紧跟着 [PersistentShell.close]（`input.close()` + `destroy()`），
 * 而 `destroy()` 会立刻 SIGTERM 掉 sh —— 最后几条命令还没有被 sh 读走/执行就随
 * 进程一起死了。实测 MI 9：cleanup 日志完整打印（含 `sh> settings put system
 * screen_off_timeout 60000` 与 `Cleanup complete`），但会话结束后
 * `settings get system screen_off_timeout` 仍停在 `2147483647` 哨兵 —— 恢复是假的。
 *
 * ── 协议 ──
 * 1. [arm] 生成唯一标记（`echo <token>` 是合法 shell 词，只含 `[A-Za-z0-9_-]`）；
 * 2. 调用方把 `echo <token>` 写进 sh 的 stdin；
 * 3. 排水线程每读到一行就喂给 [onLine]；命中标记即放行 [await]。
 *
 * stdin/stdout 都是 FIFO：标记的回显意味着**在它之前写入的命令都已执行完毕**，
 * 因此 [await] 返回后 [PersistentShell.close] 杀的是一个已经空闲的 sh。
 *
 * 标记带序号 + `nanoTime`，上一轮的迟到回显不会误放行下一轮（见单测）。
 * 超时不抛异常：只记为"恢复可能没跑完"，绝不让 cleanup 因同步失败而卡死。
 */
internal class ShellSyncPoint(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val counter = AtomicLong(0)
    private val lock = Any()

    @Volatile
    private var token: String? = null

    @Volatile
    private var latch: CountDownLatch? = null

    /** 登记一轮同步并返回要 echo 的标记。 */
    fun arm(): String {
        val t = "$PREFIX${counter.incrementAndGet()}-${System.nanoTime()}"
        synchronized(lock) {
            token = t
            latch = CountDownLatch(1)
        }
        return t
    }

    /**
     * 排水线程对 shell 的每一行输出调用。命中当前标记才放行 —— 旧标记（上一轮
     * 迟到的回显）与无关行都必须忽略，否则屏障退化成"随便什么输出都算数"。
     */
    fun onLine(line: String) {
        val current = token ?: return
        if (!line.contains(current)) return
        synchronized(lock) {
            // 二次确认：onLine 与 arm 可能并发（新会话复用同一实例时），
            // 只有仍处于本轮才 countDown。
            if (token == current) latch?.countDown()
        }
    }

    /**
     * 等标记回显。返回 false 表示超时（sh 已死 / 输出没排上队）——调用方
     * 记录后继续，不阻塞清理。
     */
    fun await(): Boolean {
        val l = latch ?: return false
        return l.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** 标记前缀；`echo $PREFIX<n>-<nanos>` 无需引号即为合法 shell 词。 */
        const val PREFIX = "__DILINK_SYNC__"

        /**
         * 默认超时。健康路径是"写一行 → sh 执行 echo → 排水线程读到"，
         * 毫秒级；2s 只在 sh 已死时才会走满，不至于拖慢 teardown。
         */
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
