package com.dilinkauto.vdserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202 C：`ensureContent` 在判定"无可见窗口"之前先过一个沉降窗口。
 *
 * 用注入的假时钟 + 固定 dumpsys 序列驱动，验证：
 *  - 窗口内出现可见任务 → 直接返回，**不发任何 `am start`**（不搬任务）；
 *  - 窗口耗尽仍无内容 → 维持原行为（按 recents 选目标恢复）。
 */
class EnsureContentSettleTest {

    /** 假时钟：sleep 只推进时间，不真睡。 */
    private class FakeClock {
        var nowNanos = 0L
        fun nanoTime(): Long = nowNanos
        fun sleep(ms: Long) { nowNanos += ms * 1_000_000L }
    }

    private class Rig(settleDumps: List<String>) {
        val execs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val clock = FakeClock()
        private val queue = java.util.concurrent.ConcurrentLinkedQueue(settleDumps)
        var recentsDump: String? = null

        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                when {
                    cmd.startsWith("dumpsys activity activities") -> queue.poll() ?: settleDumps.last()
                    cmd.startsWith("dumpsys activity recents") -> recentsDump
                    else -> null
                }
            },
            displayId = { 6 },
            onStackEmpty = { },
            setDisplayPower = { },
            requestSyncFrame = { },
            repairLayerTree = { },
            settleWindow = ContentSettleWindow(
                settleMs = 500,
                pollMs = 100,
                nanoTime = clock::nanoTime,
                sleep = clock::sleep
            )
        )
    }

    private val emptyDisplay = """
        Display #6 (activities from top to bottom):
    """.trimIndent()

    private val visibleDisplay = """
        Display #6 (activities from top to bottom):
          * Task{bbb222 #728 type=standard A=10001:com.bilibili.app.in U=0 visible=true visibleRequested=true mode=fullscreen}
            topResumedActivity=ActivityRecord{11 u0 com.bilibili.app.in/.MainActivityV2 t728}
    """.trimIndent()

    @Test
    fun `content appearing inside the settle window suppresses the restore`() {
        val r = Rig(listOf(emptyDisplay, emptyDisplay, visibleDisplay))
        r.router.ensureDisplayHasContent(6)
        assertFalse("窗口内已可见，不该搬任务: ${r.execs}", r.execs.any { it.startsWith("am start") })
        assertTrue("应走完沉降窗口的两次轮询", r.clock.nowNanos / 1_000_000L in 100L..500L)
    }

    @Test
    fun `empty display after the window still runs the old restore path`() {
        val r = Rig(listOf(emptyDisplay))
        r.recentsDump = null // 没有 recents 数据 → 只验证"窗口耗尽后继续原逻辑"
        r.router.ensureDisplayHasContent(6)
        // 原逻辑在 recents 为空时会打印"nothing to restore"，同样不发 am start。
        assertFalse(r.execs.any { it.startsWith("am start") })
        assertTrue("窗口必须耗尽 500ms", r.clock.nowNanos / 1_000_000L >= 500L)
    }
}
