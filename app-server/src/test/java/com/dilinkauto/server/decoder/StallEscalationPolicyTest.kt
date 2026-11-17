package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停顿分级（issue #200）。
 *
 * 现场（`.tmp/run-1116/client.log`）：Wi-Fi 重关联把链路静默了 5983ms，但车机端唯一能
 * 看到这件事的日志是**恢复那一帧**才打出来的 `STALL 5983ms`（[RenderStallDetector] 只在
 * 渲染推进时出报告），3s 心跳看门狗（10s 超时）也不会在 6s 内动作 —— 整段停顿里两端
 * 都没有任何可执行动作。
 *
 * 分级目标：1.5s 就能看到 `GAP` 并预置重同步（顺带经 SyncRequestGate 索要一次 IDR），
 * 3s 升级 `STALLED`。这里用注入时间线钉住边界与「每级只报一次」。
 */
class StallEscalationPolicyTest {

    @Test
    fun `首次 tick 只建基线不告警`() {
        val p = StallEscalationPolicy()
        assertNull(p.onTick(nowMs = 10_000, renders = 30))
    }

    @Test
    fun `正常帧间隔不误报`() {
        val p = StallEscalationPolicy()
        var now = 1_000L
        var renders = 30L
        repeat(30) {
            assertNull(p.onTick(nowMs = now, renders = renders))
            now += 33
            renders++
        }
    }

    @Test
    fun `复刻 issue 200 时间线：1_5 秒报 GAP、3 秒升级 STALLED`() {
        val p = StallEscalationPolicy()
        assertNull(p.onTick(nowMs = 1_000, renders = 10))
        assertNull(p.onTick(nowMs = 1_034, renders = 11))

        // 之后渲染不再推进（现场 5983ms 空档），每 33ms 一次喂帧线程 tick
        var now = 1_067L
        var gap: StallEscalationPolicy.Report? = null
        var stalled: StallEscalationPolicy.Report? = null
        while (now < 1_034L + 5_983L) {
            val r = p.onTick(nowMs = now, renders = 11)
            if (r != null) {
                if (r.level == StallEscalationPolicy.Level.GAP) {
                    assertNull("GAP 只允许报一次", gap)
                    gap = r
                } else {
                    assertNull("STALLED 只允许报一次", stalled)
                    stalled = r
                }
            }
            now += 33
        }

        assertEquals(
            "1.5s 量级必须已经能看到 GAP（现场 6s 空档里第一级）",
            StallEscalationPolicy.Level.GAP,
            gap?.level,
        )
        assertTrue("GAP 的 gapMs=${gap!!.gapMs} 应≈1500", gap.gapMs in 1_500L..1_532L)
        assertEquals(
            "3s 量级必须升级 STALLED",
            StallEscalationPolicy.Level.STALLED,
            stalled?.level,
        )
        assertTrue("STALLED 的 gapMs=${stalled!!.gapMs} 应≈3000", stalled.gapMs in 3_000L..3_032L)
        assertTrue("GAP 必须早于 STALLED", gap.gapMs < stalled.gapMs)
    }

    @Test
    fun `渲染恢复后重新起算，第二次停顿照样报`() {
        val p = StallEscalationPolicy()
        p.onTick(nowMs = 1_000, renders = 10)
        p.onTick(nowMs = 1_034, renders = 11)

        val first = p.onTick(nowMs = 2_534, renders = 11)
        assertEquals(StallEscalationPolicy.Level.GAP, first?.level)
        assertNull(p.onTick(nowMs = 2_600, renders = 11))

        // 恢复：渲染推进，基线移到 3_000
        assertNull(p.onTick(nowMs = 3_000, renders = 12))
        // 新的一次停顿从 3_000 起算
        assertNull(p.onTick(nowMs = 4_000, renders = 12))
        val second = p.onTick(nowMs = 4_500, renders = 12)
        assertEquals(StallEscalationPolicy.Level.GAP, second?.level)
        assertEquals(1_500L, second!!.gapMs)
    }

    @Test
    fun `粗粒度 tick 也按 GAP 再 STALLED 的顺序逐级升级`() {
        val p = StallEscalationPolicy()
        p.onTick(nowMs = 0, renders = 0)
        p.onTick(nowMs = 100, renders = 1)
        // 中间没有任何 tick，下一次直接跨过两个阈值
        val first = p.onTick(nowMs = 4_000, renders = 1)
        val second = p.onTick(nowMs = 4_100, renders = 1)
        assertEquals(StallEscalationPolicy.Level.GAP, first?.level)
        assertEquals(StallEscalationPolicy.Level.STALLED, second?.level)
    }

    @Test
    fun `reset 后首个 tick 重新建基线`() {
        val p = StallEscalationPolicy()
        p.onTick(nowMs = 1_000, renders = 10)
        p.onTick(nowMs = 1_034, renders = 11)
        p.onTick(nowMs = 2_534, renders = 11)

        p.reset()
        assertNull("reset 后不能把上一个会话的空档算进来", p.onTick(nowMs = 50_000, renders = 11))
        assertNull(p.onTick(nowMs = 51_000, renders = 11))
    }

    @Test
    fun `阈值可注入且默认值符合 issue 要求`() {
        assertEquals(1_500L, StallEscalationPolicy.DEFAULT_GAP_WARN_MS)
        assertEquals(3_000L, StallEscalationPolicy.DEFAULT_STALLED_MS)

        val p = StallEscalationPolicy(gapWarnMs = 500, stalledMs = 900)
        p.onTick(nowMs = 0, renders = 1)
        assertEquals(StallEscalationPolicy.Level.GAP, p.onTick(nowMs = 500, renders = 1)?.level)
        assertEquals(StallEscalationPolicy.Level.STALLED, p.onTick(nowMs = 900, renders = 1)?.level)
    }
}
