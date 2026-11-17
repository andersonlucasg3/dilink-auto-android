package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #58：车机端必须能靠日志区分「没收到帧」/「收到但没喂进去」/「丢帧」三类停顿，
 * 单帧的时序噪声不能误报。
 *
 * 用例全部走 [RenderStallDetector] 的纯逻辑，不依赖设备。
 */
class RenderStallDetectorTest {

    private fun snap(
        renders: Long,
        receive: Long = renders,
        fed: Long = renders,
        drops: Long = 0,
        inputFails: Long = 0,
        keysDropped: Long = 0,
        queueSize: Int = 0,
        awaitingIdr: Boolean = false,
        skips: Long = 0,
    ) = RenderStallDetector.Snapshot(
        renders = renders,
        receive = receive,
        fed = fed,
        drops = drops,
        inputFails = inputFails,
        keysDropped = keysDropped,
        queueSize = queueSize,
        awaitingIdr = awaitingIdr,
        skips = skips,
    )

    @Test
    fun `首次推进不告警（尚无基线）`() {
        val d = RenderStallDetector(alertMs = 1000)
        assertNull(d.onTick(nowMs = 10_000, snap = snap(renders = 30)))
    }

    @Test
    fun `间隔未达阈值不告警`() {
        val d = RenderStallDetector(alertMs = 1000)
        assertNull(d.onTick(nowMs = 1_000, snap = snap(renders = 30)))
        // 33ms 的常规帧间隔，绝不能报
        assertNull(d.onTick(nowMs = 1_033, snap = snap(renders = 60)))
        assertNull(d.onTick(nowMs = 1_066, snap = snap(renders = 90)))
    }

    @Test
    fun `间隔达到阈值产出停顿报告`() {
        val d = RenderStallDetector(alertMs = 1000)
        d.onTick(nowMs = 1_000, snap = snap(renders = 30))
        val r = d.onTick(nowMs = 2_400, snap = snap(renders = 33))

        assertNotNull(r)
        assertEquals(1_400L, r!!.gapMs)
        assertEquals(30L, r.fromRenders)
        assertEquals(33L, r.toRenders)
    }

    @Test
    fun `报告字段反映三类缺口来源`() {
        val d = RenderStallDetector(alertMs = 1000)
        // 停顿前基线：渲染 30 帧、收到 32 帧、喂了 30 帧，无丢帧
        d.onTick(nowMs = 1_000, snap = snap(renders = 30, receive = 32, fed = 30))

        // 停顿期间：又收到 5 帧，但只喂进 1 帧；丢了 4 帧、2 次输入失败、跳过 3 帧
        val r = d.onTick(
            nowMs = 2_500,
            snap = snap(
                renders = 31,
                receive = 37,
                fed = 31,
                drops = 4,
                inputFails = 2,
                keysDropped = 1,
                queueSize = 4,
                awaitingIdr = true,
                skips = 3,
            ),
        )

        assertNotNull(r)
        r!!
        assertEquals(1_500L, r.gapMs)
        assertEquals(5L, r.receiveDelta)
        assertEquals(1L, r.fedDelta)
        assertEquals(4L, r.dropDelta)
        assertEquals(2L, r.inputFailDelta)
        assertEquals(1L, r.keyDropDelta)
        assertEquals(4, r.queueSize)
        assertEquals(true, r.awaitingIdr)
        assertEquals(3L, r.skips)
    }

    @Test
    fun `完全没收到帧时增量为零`() {
        val d = RenderStallDetector(alertMs = 1000)
        d.onTick(nowMs = 1_000, snap = snap(renders = 30, receive = 32, fed = 30))
        val r = d.onTick(nowMs = 3_000, snap = snap(renders = 31, receive = 32, fed = 31))

        assertNotNull(r)
        assertEquals(0L, r!!.receiveDelta)
        assertEquals(1L, r.fedDelta)
        assertEquals(0L, r.dropDelta)
    }

    @Test
    fun `没有新渲染推进时不告警且不移动基线`() {
        val d = RenderStallDetector(alertMs = 1000)
        d.onTick(nowMs = 1_000, snap = snap(renders = 30))
        // 同一渲染帧反复 tick（空闲轮询），不应产出报告，也不该把停顿起点往后推
        assertNull(d.onTick(nowMs = 1_500, snap = snap(renders = 30)))
        assertNull(d.onTick(nowMs = 2_500, snap = snap(renders = 30)))
        // 恢复时用最初基线算时长：1_000 -> 3_000
        val r = d.onTick(nowMs = 3_000, snap = snap(renders = 31))
        assertNotNull(r)
        assertEquals(2_000L, r!!.gapMs)
    }

    @Test
    fun `一次停顿只报一次，恢复后重新起算`() {
        val d = RenderStallDetector(alertMs = 1000)
        d.onTick(nowMs = 1_000, snap = snap(renders = 30))
        assertNotNull(d.onTick(nowMs = 2_500, snap = snap(renders = 31)))
        // 紧接着的正常帧不得再报
        assertNull(d.onTick(nowMs = 2_533, snap = snap(renders = 32)))
        // 再来一次长停顿，照样能报
        assertNotNull(d.onTick(nowMs = 4_000, snap = snap(renders = 33)))
    }

    @Test
    fun `reset 后首个 tick 重新建立基线`() {
        val d = RenderStallDetector(alertMs = 1000)
        d.onTick(nowMs = 1_000, snap = snap(renders = 30))
        d.reset()
        assertNull(d.onTick(nowMs = 50_000, snap = snap(renders = 5)))
    }
}
