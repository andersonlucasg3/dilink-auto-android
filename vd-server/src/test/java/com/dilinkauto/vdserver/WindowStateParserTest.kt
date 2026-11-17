package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WindowStateParser` —— issue #64 的实车取证解析。
 *
 * 用脱敏后的真实 `dumpsys activity activities` 片段钉住：
 *  - 只解析指定 display 段（不把物理屏 display#0 的 task 混进来）；
 *  - `mLastNonfullscreenBounds=Rect(...)` / `null` 两种形态；
 *  - task 头里的 `mode=` / `visibleRequested=` 与 ActivityRecord 组件；
 *  - 指纹随窗口形态变化而变化（`[Window]` 只在变化时落日志）。
 */
class WindowStateParserTest {

    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{30edfd5 #30 type=standard A=10338:com.dilinkauto.client U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=2}
            mLastNonfullscreenBounds=Rect(372, 924 - 997, 2292)
            isSleeping=true
          * ActivityRecord{1f6675c u0 com.dilinkauto.client/.MainActivity t30}

        Display #19 (activities from top to bottom):
          * Task{ad54f40 #17 type=standard A=10001:com.baidu.BaiduMap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
            mLastNonfullscreenBounds=Rect(269, 536 - 811, 1616)
            isSleeping=false
            topResumedActivity=ActivityRecord{102f887 u0 com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity t17}
          * Task{9d1c4ff #347 type=standard A=10231:com.bilibili.app.in U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            mLastNonfullscreenBounds=Rect(920, 51 - 1641, 1351)
            mBounds=Rect(0, 0 - 2560, 1402)
            ActivityRecord{2c7e1a1 u0 com.bilibili.app.in/.MainActivity t347}
    """.trimIndent()

    @Test
    fun `parses every task in the vd display section only`() {
        val tasks = WindowStateParser.parseDisplay(dump, 19)

        assertEquals(2, tasks.size)
        assertEquals(listOf(17, 347), tasks.map { it.taskId })
        assertTrue("不应混入 display#0 的 task", tasks.none { it.taskId == 30 })
    }

    @Test
    fun `extracts bounds and window mode from a letterboxed task`() {
        val tasks = WindowStateParser.parseDisplay(dump, 19)
        val bili = tasks.first { it.taskId == 347 }

        assertEquals("com.bilibili.app.in/.MainActivity", bili.component)
        assertEquals("fullscreen", bili.windowingMode)
        assertEquals(false, bili.visibleRequested)
        assertEquals("Rect(920, 51 - 1641, 1351)", bili.lastNonfullscreenBounds)
        assertEquals("Rect(0, 0 - 2560, 1402)", bili.bounds)
    }

    @Test
    fun `missing nonfullscreen field is null rather than empty`() {
        val sparse = """
            Display #2 (activities from top to bottom):
              * Task{aaa #5 type=standard A=1000:com.example U=0 visible=true visibleRequested=true mode=fullscreen}
                mLastNonfullscreenBounds=null
        """.trimIndent()

        val task = WindowStateParser.parseDisplay(sparse, 2).single()
        assertEquals("null", task.lastNonfullscreenBounds)
        assertNull("mBounds 缺失应为 null", task.bounds)
        assertNull("没有 ActivityRecord 行时组件为 null", task.component)
    }

    @Test
    fun `returns empty when the display is absent`() {
        assertTrue(WindowStateParser.parseDisplay(dump, 77).isEmpty())
    }

    @Test
    fun `fingerprint changes when nonfullscreen bounds change`() {
        val before = WindowStateParser.fingerprint(WindowStateParser.parseDisplay(dump, 19))
        val after = WindowStateParser.fingerprint(
            WindowStateParser.parseDisplay(
                dump.replace("Rect(920, 51 - 1641, 1351)", "null"),
                19
            )
        )

        assertTrue("小窗消失必须导致指纹变化", before != after)
    }

    /**
     * issue #77 的墓碑：同一 task 的引用行（`rootOfTask=` / `topDisplayFocusedRootTask=`）不得
     * 让同一 id 被解析多次，`[Window]` 的 `n=` 才等于真实 task 数。
     *
     * 修复前 `parseDisplay(#0)` 返回 3 个条目（`[1,1,1]`）—— 一个 `* Task{` 头加两条引用行。
     * 修复后只有 1 个（`[1]`）。期望值是字面量，不由被测函数反推。
     */
    @Test
    fun `issue 77 counts a task header once when reference lines repeat it`() {
        val withReferences = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              * Task{aaa111 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
                rootOfTask=true task=Task{aaa111 #1 type=home}
                topDisplayFocusedRootTask=Task{aaa111 #1 type=home}
        """.trimIndent()

        val tasks = WindowStateParser.parseDisplay(withReferences, 0)

        assertEquals(listOf(1), tasks.map { it.taskId })
        assertEquals("模式与可见性必须取自 task 头，而不是引用行", "fullscreen", tasks.single().windowingMode)
        assertEquals(true, tasks.single().visibleRequested)
    }
}