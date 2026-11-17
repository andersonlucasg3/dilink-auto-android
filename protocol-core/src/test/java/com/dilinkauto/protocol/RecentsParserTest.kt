package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [RecentsParser]：`dumpsys activity recents` 的解析（活任务过滤、组件
 * 提取、shell 注入防护）与「快速切换」目标选择（排除当前顶层、自家控制端、
 * launcher/systemui）。
 *
 * 样本取自 2026-10-10 MI 9 / Android 15 实机 `dumpsys activity recents` 的
 * 真实输出（含开头的 `taskId=... rootTaskId=...` 概要段，它必须不被误解析）。
 */
class RecentsParserTest {

    /** 实机样本：3 个活任务（twitter / 控制端 / 小红书）+ 3 个已死任务。 */
    private val realSample = """
Activity manager recents dump:
  taskId=2048 rootTaskId=2048
  lastActiveTime=849748141 (inactive for 26s)
  taskId=2037 rootTaskId=1
  lastActiveTime=849745263 (inactive for 29s)
  * RecentTaskInfo #0: 
    id=2048 userId=0 hasTask=true lastActiveTime=849748141
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.twitter.android/com.x.android.main.MainActivity }
    baseActivity={com.twitter.android/com.x.android.main.MainActivity}
    realActivity={com.twitter.android/com.x.android.main.MainActivity}
    affinity=10216
  * RecentTaskInfo #1: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #2: 
    id=2047 userId=0 hasTask=true lastActiveTime=848968398
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.xingin.xhs/.index.v2.IndexActivityV2 }
  * RecentTaskInfo #3: 
    id=2023 userId=0 hasTask=false lastActiveTime=848492633
    baseIntent=Intent { flg=0x10000000 cmp=com.android.settings/.Settings }
  * RecentTaskInfo #4: 
    id=2012 userId=0 hasTask=false lastActiveTime=848492632
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 cmp=moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity }
  * RecentTaskInfo #5: 
    id=2007 userId=0 hasTask=false lastActiveTime=848492631
    baseIntent=Intent { flg=0x34000000 cmp=com.github.metacubex.clash.meta/com.github.kr328.clash.MainActivity }
""".trimIndent()

    @Test
    fun parsesLiveTasksInOrderAndSkipsDeadOnes() {
        val tasks = RecentsParser.parse(realSample)
        assertEquals(3, tasks.size)
        assertEquals(RecentTask(2048, "com.twitter.android/com.x.android.main.MainActivity", "com.twitter.android"), tasks[0])
        assertEquals(RecentTask(2040, "com.dilinkauto.client/.MainActivity", "com.dilinkauto.client"), tasks[1])
        assertEquals(RecentTask(2047, "com.xingin.xhs/.index.v2.IndexActivityV2", "com.xingin.xhs"), tasks[2])
    }

    @Test
    fun summarySectionIsNotParsedAsBlocks() {
        // 概要段（taskId=/lastActiveTime=）不得产生任何任务。
        val tasks = RecentsParser.parse("  taskId=2048 rootTaskId=2048\n  lastActiveTime=849748141 (inactive for 26s)\n")
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun baseIntentWithoutComponentIsDropped() {
        val dump = """
  * RecentTaskInfo #0: 
    id=100 userId=0 hasTask=true lastActiveTime=1
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }
""".trimIndent()
        assertTrue(RecentsParser.parse(dump).isEmpty())
    }

    @Test
    fun unsafeComponentShapesAreRejected() {
        // 值里带 shell 元字符的一律拒绝（该组件会拼进 shell UID 的 am start 行）。
        val dump = """
  * RecentTaskInfo #0: 
    id=100 userId=0 hasTask=true
    baseIntent=Intent { cmp=com.foo/.Bar;rm -rf / }
  * RecentTaskInfo #1: 
    id=101 userId=0 hasTask=true
    baseIntent=Intent { cmp=com.foo/.Bar`id` }
""".trimIndent()
        assertTrue(RecentsParser.parse(dump).isEmpty())
    }

    @Test
    fun malformedIdIsDropped() {
        val dump = """
  * RecentTaskInfo #0: 
    id=None userId=0 hasTask=true
    baseIntent=Intent { cmp=com.foo/.Bar }
""".trimIndent()
        assertTrue(RecentsParser.parse(dump).isEmpty())
    }

    @Test
    fun emptyAndGarbageDumpYieldNothing() {
        assertTrue(RecentsParser.parse("").isEmpty())
        assertTrue(RecentsParser.parse("garbage\nwithout\nblocks").isEmpty())
    }

    // ── pickQuickSwitchTarget ──

    private val tasks = listOf(
        RecentTask(2048, "com.twitter.android/com.x.android.main.MainActivity", "com.twitter.android"),
        RecentTask(2040, "com.dilinkauto.client/.MainActivity", "com.dilinkauto.client"),
        RecentTask(2047, "com.xingin.xhs/.index.v2.IndexActivityV2", "com.xingin.xhs"),
    )

    @Test
    fun picksMostRecentTaskThatIsNotCurrentOrSelf() {
        // 当前顶层 = twitter → 跳过它和控制端 → 命中小红书。
        val t = RecentsParser.pickQuickSwitchTarget(tasks, currentTaskId = 2048, selfPackage = "com.dilinkauto.client")
        assertEquals(2047, t?.taskId)
    }

    @Test
    fun skipsLauncherAndSystemUi() {
        val list = listOf(
            RecentTask(3000, "com.google.android.apps.nexuslauncher/.NexusLauncherActivity", "com.google.android.apps.nexuslauncher"),
            RecentTask(3001, "com.android.systemui/.RecentsActivity", "com.android.systemui"),
            RecentTask(3002, "com.foo/.MainActivity", "com.foo"),
        )
        val t = RecentsParser.pickQuickSwitchTarget(list, currentTaskId = null, selfPackage = "com.dilinkauto.client")
        assertEquals(3002, t?.taskId)
    }

    @Test
    fun unknownCurrentDoesNotExcludeAnything() {
        val t = RecentsParser.pickQuickSwitchTarget(tasks, currentTaskId = null, selfPackage = "com.dilinkauto.client")
        assertEquals(2048, t?.taskId)
    }

    @Test
    fun allFilteredOutYieldsNull() {
        val list = listOf(
            RecentTask(2040, "com.dilinkauto.client/.MainActivity", "com.dilinkauto.client"),
            RecentTask(2048, "com.twitter.android/com.x.android.main.MainActivity", "com.twitter.android"),
        )
        assertNull(RecentsParser.pickQuickSwitchTarget(list, currentTaskId = 2048, selfPackage = "com.dilinkauto.client"))
    }
}
