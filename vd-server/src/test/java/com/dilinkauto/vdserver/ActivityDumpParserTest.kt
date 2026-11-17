package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityDumpParser` —— issue #77 的段落切分与 task 提取（两处复制实现的唯一来源）。
 *
 * 用按**实机格式**重建的 dump 钉住三个缺陷的修复：
 *  - (a) 目标 display 是最后一个时不再吃到字符串末尾（跨屏泄漏）；
 *  - (b) `Task{…#N …}` 的引用行（`rootOfTask=` / `topDisplayFocusedRootTask=` / `PreferredTopFocusableRootTask=`）
 *        不再被当成 task 头，同一 id 只计一次；
 *  - (c) 全局 `ResumedActivity:` 行（整份 dump 只有一条，值 = 当前焦点 display 的 resumed activity）
 *        不再被算进「恰好排在最后」那个 display 的段里。
 *
 * 期望值全部写字面量，不由被测函数反推。
 */
class ActivityDumpParserTest {

    /**
     * 按 `debug-logs/dpi-repro-20261103-213130/activities-after-eject-xhs.txt` 的结构重建：
     * 每个 display 段内部有自己的 `Resumed activities in task display areas` / `Resumed:` 行
     * （实机 `:482-483` 属 #20），段外、`ActivityTaskSupervisor state:` 之前有一条**全局**
     * `ResumedActivity:`（实机 `:485`，值 `t10` 是物理屏的 XHS）。
     *
     * 修复前（`displaySection` 无上界 + `Task\{[^}]*#(\d+)[ }]`）的结果：
     *   display #0 → emitted=[1,1,1]                       → 期望 [1]
     *   display #9 → emitted=[178,178,178,178,10,345,345]  → 期望 [178]
     * `10`（全局行与 `ActivityTaskSupervisor` 的引用）与 `345`（物理屏 shizuku 任务）都不属于 #9。
     */
    private val syntheticDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{aaa111 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{aaa111 #1 type=home}
            topDisplayFocusedRootTask=Task{aaa111 #1 type=home}

          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{11 u0 com.android.launcher/.Launcher t1}

        Display #9 (activities from top to bottom):

          * Task{bbb222 #178 type=standard A=10001:com.example U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{bbb222 #178 type=standard A=10001:com.example}
            topDisplayFocusedRootTask=Task{bbb222 #178 type=standard A=10001:com.example}
            PreferredTopFocusableRootTask=Task{bbb222 #178 type=standard A=10001:com.example}

          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{22 u0 com.example/.Main t178}

          ResumedActivity: ActivityRecord{33 u0 com.xingin.xhs/.IndexActivityV2 t10}

        ActivityTaskSupervisor state:
          topDisplayFocusedRootTask=Task{cc2fd5b #10 type=standard A=10235:com.xingin.xhs}
          Display: mDisplayId=0 (organized) init=1368x3192 560dpi
            * Task{ccc333 #345 type=standard A=10334:moe.shizuku}
              rootOfTask=true task=Task{ccc333 #345 type=standard A=10334:moe.shizuku}
    """.trimIndent()

    /** 目标是最后一个 display 时，切片不得吞进后续物理屏内容与全局行。 */
    @Test
    fun `last display section stops before the physical screen and the global resumed activity line`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 9)!!

        assertTrue("不得跨屏吞进物理屏记述", !section.contains("Display: mDisplayId="))
        assertTrue("不得跨屏吞进物理屏任务", !section.contains("#345"))
        assertTrue("全局 ResumedActivity 行不属于任何一个 display 段", !section.contains("ResumedActivity:"))
        assertTrue("段内内容本身要保留", section.contains("#178"))
    }

    /** display #0：期望 `[1]`（修复前是 `[1,1,1]`）。 */
    @Test
    fun `display zero counts each task once`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 0)!!
        assertEquals(listOf(1), ActivityDumpParser.taskIdsInSection(section))
    }

    /** display #9：期望 `[178]`（修复前是 `[178,178,178,178,10,345,345]`）。 */
    @Test
    fun `display nine counts each task once and excludes the leaked physical screen task`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 9)!!
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(section))
    }

    /**
     * P1-1 的墓碑：最后一个 display 段之后紧跟一条**指向别的 display** 的全局
     * `ResumedActivity:` 行（真实形态：物理屏是焦点时，该行指向物理屏 task）。
     *
     * 只钉一件事 —— 全局行的 `t<id>` 不得进入前面那个 display 的 task 列表。
     */
    @Test
    fun `global resumed activity line never joins the preceding last display section`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{p1 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen}

            Display #20 (activities from top to bottom):
              * Task{s1 #17 type=standard A=10001:com.baidu.BaiduMap U=0 visible=true visibleRequested=true mode=fullscreen}

              ResumedActivity: ActivityRecord{d9661ef u0 com.xingin.xhs/.IndexActivityV2 t10}

            ActivityTaskSupervisor state:
              topDisplayFocusedRootTask=Task{cc2fd5b #10 type=standard A=10235:com.xingin.xhs}
        """.trimIndent()

        val section = ActivityDumpParser.displaySection(dump, 20)!!

        assertEquals("物理屏的 t10 不得算成 VD 的残留任务", listOf(17), ActivityDumpParser.taskIdsInSection(section))
    }

    /** 段内 `emitted == distinct`：同一 id 只出现一次。 */
    @Test
    fun `emitted equals distinct for both displays`() {
        for (id in listOf(0, 9)) {
            val ids = ActivityDumpParser.taskIdsInSection(ActivityDumpParser.displaySection(syntheticDump, id)!!)
            assertEquals("display #$id 每个 task 只应出现一次", ids.size, ids.toSet().size)
        }
    }

    /** 只认 task 头：`rootOfTask=` / `topDisplayFocusedRootTask=` 这类引用行不得计入。 */
    @Test
    fun `reference lines are not counted as task headers`() {
        // 只有引用行、没有 `* Task{` 头的段 —— 头匹配必须为空。
        val onlyRefs = """
            Display #9 (activities from top to bottom):
              rootOfTask=true task=Task{bbb222 #178 type=home}
              topDisplayFocusedRootTask=Task{ccc333 #345 type=home}
        """.trimIndent()

        assertTrue(
            "引用行不得被当成 task 头",
            ActivityDumpParser.taskHeaders(onlyRefs).isEmpty()
        )
    }

    /** 头 + 引用指向同一 id 时按首次出现去重。 */
    @Test
    fun `header and reference with the same id collapse to one`() {
        val mixed = """
            Display #9 (activities from top to bottom):
              * Task{bbb222 #178 type=home visibleRequested=true mode=fullscreen}
                rootOfTask=true task=Task{bbb222 #178 type=home}
                * Hist  #0: ActivityRecord{22 u0 com.example/.Main t178}
        """.trimIndent()

        val headers = ActivityDumpParser.taskHeaders(mixed)
        assertEquals(1, headers.size)
        assertEquals("178", headers.single().groupValues[1])
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(mixed))
    }

    /** `taskHeaderMatcher(id)` 与 [ActivityDumpParser.TASK_HEADER_RE] 同一模式，只内插 id。 */
    @Test
    fun `single id matcher agrees with the shared header regex`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 9)!!

        assertTrue(ActivityDumpParser.taskHeaderMatcher(178).containsMatchIn(section))
        assertTrue("不得命中同前缀的其它 id", !ActivityDumpParser.taskHeaderMatcher(17).containsMatchIn(section))
        assertTrue("引用行不得命中", !ActivityDumpParser.taskHeaderMatcher(345).containsMatchIn(section))
    }

    /** 段内有多个 task 头时按正文先后返回。 */
    @Test
    fun `multiple headers keep document order`() {
        val two = """
            Display #19 (activities from top to bottom):
              * Task{a #17 type=standard visibleRequested=true mode=fullscreen}
              * Task{b #347 type=standard visibleRequested=false mode=fullscreen}
        """.trimIndent()

        assertEquals(listOf(17, 347), ActivityDumpParser.taskIdsInSection(two))
    }

    /** 中间位置的目标 display：上界是下一个 `Display #` 段头。 */
    @Test
    fun `middle display stops at the next display header`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 0)!!
        assertTrue("不得吞进下一个 display 段", !section.contains("Display #9 "))
    }

    /** display 不存在时返回 null。 */
    @Test
    fun `absent display yields null section`() {
        assertNull(ActivityDumpParser.displaySection(syntheticDump, 77))
    }

    /** 段里一个 task 都没有时返回空列表。 */
    @Test
    fun `display without tasks yields empty id list`() {
        val empty = """
            Display #7 (activities from top to bottom):

            ActivityTaskSupervisor state:
              topDisplayFocusedRootTask=Task{aaa #1 type=home}
        """.trimIndent()

        assertEquals(emptyList<Int>(), ActivityDumpParser.taskIdsInSection(ActivityDumpParser.displaySection(empty, 7)!!))
    }

    /**
     * 行首的全局段标记构成上界；缩进的同名文字是**段内正文**，不是上界。
     *
     * 若把缩进的 `ActivityTaskSupervisor state:` 当上界就会把 `178` 一并切掉，反而漏认。
     */
    @Test
    fun `indented supervisor text stays inside the section while column zero marker bounds it`() {
        val dump = """
            Display #9 (activities from top to bottom):
              ActivityTaskSupervisor state: topDisplayFocusedRootTask=Task{bbb222 #178 type=home}
              * Task{bbb222 #178 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            ActivityTaskSupervisor state:
              topDisplayFocusedRootTask=Task{aaa #1 type=home}
        """.trimIndent()

        val section = ActivityDumpParser.displaySection(dump, 9)!!
        assertTrue("缩进的同名文字不得构成上界", section.contains("#178"))
        assertTrue("行首全局标记之后的内容不得进入段内", !section.contains("Task{aaa #1"))
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(section))
    }
}
