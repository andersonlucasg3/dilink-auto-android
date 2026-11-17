package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #19：VD 建好后 DTA 被踢出 display 层树时的检测/修复钉子。
 *
 * 实车证据（issue #19）：`LAUNCH_APP` 的 `am task resize … fill` 之后约 1.6s，
 * `DefaultTaskDisplayArea` 孤立到 SF 根的 `Offscreen Hierarchy` 下 → 该 display
 * 合成 0 层 → 车机黑屏 10-15s。修复是 `wm user-rotation -d <id>` 往返一次
 * （lock 1 → lock 0），把层树事务逼回来。
 *
 * 这些用例把「检测结果 → 要不要做旋转往返」的决策钉住：探针与等待都注入，
 * 不依赖设备，也不会真的睡 6 秒。
 */
class VirtualDisplayCreatorDtaTest {

    private class Rig(private val probe: () -> Boolean?) {
        val shells = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()

        val creator = VirtualDisplayCreator(
            displayWidth = 2340,
            displayHeight = 1316,
            dpi = 160,
            execShell = { shells += it },
            execShellOutput = { null },
            log = { },
            err = { },
            layersAttached = probe,
            sleepMs = { sleeps += it },
        )
    }

    @Test
    fun `orphaned layer tree is repaired with exactly one rotation round trip`() {
        // 首次读到孤立，修复后读到健康 —— 只应做一轮往返，不做第二轮。
        var calls = 0
        val r = Rig { if (calls++ == 0) false else true }
        r.creator.ensureDtaAttached(targetDisplayId = 7)

        assertEquals(
            listOf("wm user-rotation -d 7 lock 1", "wm user-rotation -d 7 lock 0"),
            r.shells,
        )
        assertEquals("往返之间必须留出 WM 重新提交事务的时间", listOf(1500L, 1500L), r.sleeps)
    }

    @Test
    fun `healthy layer tree triggers no rotation at all`() {
        // 健康设备上检测通过即返回，零副作用（这是把修复挂在 fill 之后的成本前提）。
        val r = Rig { true }
        r.creator.ensureDtaAttached(targetDisplayId = 7)

        assertTrue("健康路径不得下发任何 wm 命令: ${r.shells}", r.shells.isEmpty())
        assertTrue("健康路径不得等待", r.sleeps.isEmpty())
    }

    @Test
    fun `unknown display id skips the probe and every command`() {
        var probed = false
        val r = Rig { probed = true; true }
        r.creator.ensureDtaAttached(targetDisplayId = -1)

        assertTrue("display id 未知时连 dumpsys 都不该跑", !probed)
        assertTrue(r.shells.isEmpty())
    }

    @Test
    fun `still orphaned after two rounds reports failure instead of looping forever`() {
        // 修复无效时（ROM 上旋转往返也拉不回 DTA）只允许 2 轮，之后放弃并交给
        // 车机侧的自愈阶梯（issue #15），不能在这里无限重试把后台线程占死。
        val r = Rig { false }
        r.creator.ensureDtaAttached(targetDisplayId = 7)

        assertEquals(4, r.shells.size)
        assertEquals(6000L, r.sleeps.sum())
    }
}
