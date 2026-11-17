package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #77 的墓碑，走 [CarCommandRouter.logTeardownDisplaySnapshot] 的
 * `[teardown-display] residualTasks=` 输出 —— 那是 #48 判断「哪些任务没被搬回物理屏」的唯一依据。
 *
 * 文件名带 `RedFirst` 是因为它**刻意只依赖 base 已存在的生产代码**：把本文件拷进 pristine base
 * （`git archive main`）即可编译并在测试执行阶段真实失败，失败签名与 issue 症状同型：
 *   display #0 → residualTasks=[1,1,1]
 *   display #9 → residualTasks=[178,178,178,178,10,345,345]（期望 [178]）
 * 打补丁后同文件转绿。期望值全部是字面量，不由被测函数反推。
 *
 * fixture 按实机格式（`debug-logs/dpi-repro-20261103-213130/activities-after-eject-xhs.txt`）重建：
 * `Resumed:` 行在 display 段**内部**（每屏自己的），全局 `ResumedActivity:` 行在段**外**
 * 且指向另一个 display 的 task（P1-1）。
 */
class Issue77RedFirstTest {

    /**
     * `Display #9` 段内只有 task 头与其引用行，加一条段内 `Resumed: …t178`；
     * 段外有一条指向物理屏的全局 `ResumedActivity: …t10`，段后紧跟物理屏记述与 `#345`。
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

    private class Harness(val activitiesDump: String, val displayId: Int) {
        val router = CarCommandRouter(
            exec = { },
            execOut = { cmd ->
                when {
                    cmd.startsWith("dumpsys activity activities") -> activitiesDump
                    cmd.startsWith("dumpsys window displays") ->
                        "Display: mDisplayId=0\n  init=1368x3192 560dpi cur=1368x3192\n"
                    else -> null
                }
            },
            displayId = { displayId },
            onStackEmpty = { },
            setDisplayPower = { },
            requestSyncFrame = { },
            repairLayerTree = { },
        )
    }

    private fun snapshotLog(displayId: Int): String {
        val h = Harness(syntheticDump, displayId)
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            h.router.logTeardownDisplaySnapshot(displayId)
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    /** display #0：期望 `residualTasks=[1]`（修复前 `[1,1,1]`）。 */
    @Test
    fun `issue 77 display zero reports each task once`() {
        assertTrue(
            "display#0 的残留任务必须每个只出现一次",
            snapshotLog(0).contains("residualTasks=[1]")
        )
    }

    /**
     * display #9：残留列表必须只含 VD 自己的 task。
     *
     * fixture 里另有两个别的 display 的 task：`345`（物理屏记述里的 task 头）与
     * `10`（全局 `ResumedActivity:` 行 + `ActivityTaskSupervisor` 的引用）。两者都不得计入。
     */
    @Test
    fun `issue 77 display nine reports each task once and excludes other displays tasks`() {
        val ids = residualIds(snapshotLog(9))

        assertEquals("残留列表必须只含 VD 自己的 task", listOf("178"), ids)
    }

    /** 同一 dump 下残留列表没有重复 id（`emitted == distinct` 的墓碑）。 */
    @Test
    fun `issue 77 residual task list is duplicate free`() {
        val ids = residualIds(snapshotLog(9))

        assertEquals("每个 task 只应出现一次", ids.size, ids.toSet().size)
    }

    /** 取 `[teardown-display] … residualTasks=[a, b]` 里的 id 列表。 */
    private fun residualIds(log: String): List<String> =
        Regex("residualTasks=\\[([^\\]]*)\\]").find(log)!!.groupValues[1]
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
