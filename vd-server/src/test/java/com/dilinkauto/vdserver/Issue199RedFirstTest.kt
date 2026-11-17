package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * issue #199 的红基线：这两条断言在**未打补丁的 base** 上必须先红。
 *
 * 只用 base 上已存在的 API（`moveAllTasksToDisplay(from, to)` 与
 * `ProcessWatchdog.WATCHDOG_GRACE_MS`），所以能在 base 上编译、并按现场症状失败：
 *  1. base 的批量搬迁对**每个** task 都执行一次整份 `dumpsys activity activities`
 *     （外加每个 task 一次 300ms 沉降）—— 现场 3 个 task 因此跑到 3079ms；
 *  2. base 的 watchdog 宽限 3000ms 小于收尾需要的预算，一步慢就把后 13 步整段 halt。
 */
class Issue199RedFirstTest {

    @Test
    fun `teardown move all must read the activities dump only once`() {
        val activitiesDump = """
Display #0 (activities from top to bottom):
  * Task{a11 #0 type=standard A=10216:com.dilinkauto.client}
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
  * Task{b22 #2047 type=standard A=10235:com.xingin.xhs U=0 visible=false}
    * ActivityRecord{751a1f6 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t2047}
"""
        val execs = CopyOnWriteArrayList<String>()
        val execOuts = CopyOnWriteArrayList<String>()
        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity activities") -> activitiesDump
                    cmd.startsWith("wm size") -> "Physical size: 2340x1316"
                    cmd.startsWith("am task resize") -> ""
                    else -> null
                }
            },
            displayId = { 7 },
            onStackEmpty = {},
            setDisplayPower = {},
            requestSyncFrame = {},
            repairLayerTree = {},
            sdkInt = { 29 }
        )

        router.moveAllTasksToDisplay(7, 0)

        assertEquals(
            "批量搬迁固定只读 2 次 dumpsys（1 次规划 + 1 次批量回读），不能随 task 数增长；" +
                "base 是 1 + N 次，这才是 apps-moved 3079ms 的主因",
            2,
            execOuts.count { it.startsWith("dumpsys activity activities") }
        )
        assertEquals(
            "每个 task 仍然必须搬回物理屏",
            listOf("am display move-stack 2048 0", "am display move-stack 2047 0"),
            execs.filter { it.startsWith("am display move-stack") }
        )
    }

    @Test
    fun `watchdog grace must leave room for the teardown budget`() {
        assertTrue(
            "base 的 3s 宽限小于收尾预算：一步慢整段被 halt。当前=${ProcessWatchdog.WATCHDOG_GRACE_MS}ms",
            ProcessWatchdog.WATCHDOG_GRACE_MS > 4_000L
        )
    }
}
