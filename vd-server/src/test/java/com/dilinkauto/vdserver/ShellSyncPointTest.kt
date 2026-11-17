package com.dilinkauto.vdserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellSyncPoint] 的契约测试（2026-10-10）。
 *
 * 背景：cleanup() 末尾的恢复命令是 fire-and-forget 写入常驻 sh 的，随后立刻
 * `destroy()` —— sh 没执行完就死，`settings put system screen_off_timeout 60000`
 * 实测被丢。屏障的语义是"标记回显 ⇒ 此前命令都已执行"，所以这几条必须钉死：
 *  - 命中标记才放行；
 *  - 无关行 / 旧标记不得放行（否则屏障退化成任意输出都算数）；
 *  - 超时返回 false 且不抛（cleanup 不能被同步失败挂死）；
 *  - 标记必须是合法 shell 词（要原样 echo，不能带引号/特殊字符）。
 */
class ShellSyncPointTest {

    /** 默认超时对测试太久；用毫秒级构造。 */
    private fun point(timeoutMs: Long = 150L) = ShellSyncPoint(timeoutMs)

    @Test
    fun `unarmed point does not release`() {
        assertFalse("没 arm 就没有等待中的标记，必须立刻返回 false", point().await())
    }

    @Test
    fun `armed marker releases the await`() {
        val p = point()
        val token = p.arm()
        Thread {
            Thread.sleep(30)
            // 排水线程实际看到的形式带前缀
            p.onLine("sh<out| $token")
        }.start()
        assertTrue(p.await())
    }

    @Test
    fun `await times out when the marker never arrives`() {
        val p = point(120L)
        p.arm()
        val started = System.currentTimeMillis()
        assertFalse(p.await())
        // 只是"等到超时"，不是永久阻塞
        assertTrue("必须按 timeout 返回", System.currentTimeMillis() - started >= 100L)
    }

    @Test
    fun `unrelated lines do not release`() {
        val p = point(120L)
        p.arm()
        p.onLine("sh<out| hello world")
        p.onLine("__DILINK_SYNC__") // 前缀本身不足以命中（缺本轮序号）
        p.onLine("sh<err| settings: not found")
        assertFalse(p.await())
    }

    @Test
    fun `stale marker from an earlier round does not release the next round`() {
        val p = point(120L)
        val first = p.arm()
        p.onLine("sh<out| $first")
        assertTrue("第一轮应放行", p.await())

        val second = p.arm()
        assertTrue("两轮标记必须不同", first != second)
        p.onLine("sh<out| $first") // 上一轮迟到的回显
        assertFalse("迟到回显不得放行新一轮", p.await())
    }

    @Test
    fun `token is a plain shell word`() {
        val token = point().arm()
        assertTrue("token 要能原样 echo 进 sh，不允许引号/特殊字符：$token", token.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue("必须带前缀，便于日志辨认", token.startsWith(ShellSyncPoint.PREFIX))
    }
}
