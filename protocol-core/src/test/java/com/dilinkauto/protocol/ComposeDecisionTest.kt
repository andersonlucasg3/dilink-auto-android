package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #153-PA 的契约墓碑：静止画面下「本拍是否合成」的决策。
 *
 * 钉住三条不可回退的性质：
 *  1. 有新帧必 DRAW（恢复首帧不能被保活相位吞掉）；
 *  2. 连续无新帧只在 ≤500ms 的间隔上走 KEEP_ALIVE，其余全 SKIP；
 *  3. 任何异常输入（间隔非正、时钟回退、会话首拍）都不会导致「永远不合成」。
 */
class ComposeDecisionTest {

    private val intervalNs = VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS * 1_000_000L
    private val t0 = 1_000_000_000_000L

    private data class Case(
        val name: String,
        val hasNewFrame: Boolean,
        val nowNanos: Long,
        val lastComposeNanos: Long,
        val interval: Long,
        val expected: ComposeDecision.Action,
    )

    @Test
    fun `decide is table driven`() {
        val cases = listOf(
            Case("有新帧必 DRAW（刚合成过）", true, t0, t0 - 1L, intervalNs, ComposeDecision.Action.DRAW),
            Case("有新帧必 DRAW（刚 SKIP 过）", true, t0 + 10 * intervalNs, t0, intervalNs, ComposeDecision.Action.DRAW),
            Case("会话首拍无新帧兜底 KEEP_ALIVE", false, t0, 0L, intervalNs, ComposeDecision.Action.KEEP_ALIVE),
            Case("距上次合成不足间隔 → SKIP", false, t0 + intervalNs - 1L, t0, intervalNs, ComposeDecision.Action.SKIP),
            Case("恰好等于间隔 → KEEP_ALIVE", false, t0 + intervalNs, t0, intervalNs, ComposeDecision.Action.KEEP_ALIVE),
            Case("超过间隔 → KEEP_ALIVE", false, t0 + 3 * intervalNs, t0, intervalNs, ComposeDecision.Action.KEEP_ALIVE),
            Case("时钟回退 → SKIP 而非误判到期", false, t0 - 1L, t0, intervalNs, ComposeDecision.Action.SKIP),
            Case("间隔为 0 → 每拍 KEEP_ALIVE（降级路径）", false, t0 + 10 * intervalNs, t0, 0L, ComposeDecision.Action.KEEP_ALIVE),
            Case("间隔为负 → 每拍 KEEP_ALIVE", false, t0 + 10 * intervalNs, t0, -1L, ComposeDecision.Action.KEEP_ALIVE),
            Case("间隔非正时仍有新帧 → DRAW 优先", true, t0 + 10 * intervalNs, t0, 0L, ComposeDecision.Action.DRAW),
        )
        for (c in cases) {
            assertEquals(
                c.name,
                c.expected,
                ComposeDecision.decide(c.hasNewFrame, c.nowNanos, c.lastComposeNanos, c.interval),
            )
        }
    }

    @Test
    fun `continuous idle skips until keep alive then resumes skipping`() {
        var lastCompose = 0L
        val actions = mutableListOf<ComposeDecision.Action>()
        // 20fps 下 0..2s 含两端共 41 拍，全程无新帧。
        for (tick in 0 until 41) {
            val now = t0 + tick * 50_000_000L
            val a = ComposeDecision.decide(false, now, lastCompose, intervalNs)
            if (a != ComposeDecision.Action.SKIP) lastCompose = now
            actions += a
        }
        assertEquals("首拍必须保活", ComposeDecision.Action.KEEP_ALIVE, actions.first())
        assertEquals(
            "2 秒内只应有首拍 + 每 500ms 一次保活 = 5 次合成",
            5,
            actions.count { it != ComposeDecision.Action.SKIP },
        )
        assertEquals("其余 36 拍全部短路", 36, actions.count { it == ComposeDecision.Action.SKIP })
        assertEquals("静止期间不应取新纹理", 0, actions.count { it == ComposeDecision.Action.DRAW })
    }

    @Test
    fun `recovery first frame always draws regardless of keep alive phase`() {
        // 在刚保活过、以及刚 SKIP 过两种相位下恢复，都必须立刻 DRAW。
        for (lastCompose in listOf(t0, t0 + 3 * intervalNs)) {
            val now = lastCompose + 1L
            assertEquals(
                ComposeDecision.Action.DRAW,
                ComposeDecision.decide(true, now, lastCompose, intervalNs),
            )
        }
    }

    @Test
    fun `keep alive constant is shared with encoder repeat interval`() {
        // VideoEncoderSelector 用 ms * 1000 换算成 µs；这里钉住换算结果，
        // 防止有人只改一侧造成「短路间隔 > 补帧间隔」的空窗。
        assertEquals(500L, VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS)
        assertEquals(500_000L, VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS * 1000L)
        assertEquals(500_000_000L, VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS * 1_000_000L)
    }
}
