package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IDR 补帧请求闸门的纯逻辑钉子（issue #163，继承 issue #75 的「两端单一来源」约束）。
 *
 * 缺陷形态：旧闸门是「距上次放行 ≥1s」的硬性间隔（等价于窗口内只放行 1 次）。现场
 * 2026-11-14 20:19 的日志里，第 8 次重同步在 305ms 前刚恢复、随即再次断链，第二次请求被
 * `IDR request throttled (last request 298ms ago, min 1000ms)` 吞掉，只能干等 3s 自然
 * GOP，跳过 87 个 P 帧、冻结 2894ms。
 *
 * 修复后：[SyncRequestGate] 以 [VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS] 为滑动窗口，
 * 窗口内最多放行 [VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW] 次。本文件钉住窗口语义、
 * 两端同判、以及「二次缺口不再被吞」这三件事。
 *
 * 为什么窗口长度仍是 1000、次数提到 2：手机侧 1s 是 issue #3 用来打断「IDR 突发打满链路」
 * 正反馈的闸门，issue #70 又把自然 GOP 拉到 3s；只放开次数不放开窗口，既补二次缺口，
 * 又不重新冲击仍 OPEN 的 P1（issue #55）。
 */
class SyncFrameRequestGateTest {

    @Test
    fun `window shape is pinned to one second and two grants`() {
        // 两端唯一来源；改动即两端同时变，绝不允许再出现第二份常量。
        assertEquals(1000L, VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS)
        assertEquals(2, VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW)
        assertEquals(VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS, SyncRequestGate().windowMs)
        assertEquals(VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW, SyncRequestGate().maxPerWindow)
    }

    @Test
    fun `first grant is allowed regardless of the absolute clock`() {
        // 车机用 elapsedRealtime，开机几小时后是任意大数；空状态必须直接放行，
        // 不能像旧实现那样依赖「0 当哨兵」再被绝对时钟绊倒。
        assertNotNull(SyncRequestGate().tryAcquire(nowMs = 987_654_321L))
    }

    @Test
    fun `two grants inside one window then the third is refused`() {
        val fresh = SyncRequestGate()
        val afterFirst = fresh.tryAcquire(0L)
        assertNotNull("窗口内第 1 次必须放行", afterFirst)
        val afterSecond = afterFirst!!.tryAcquire(305L)
        assertNotNull("恢复 305ms 后又断链，窗口内第 2 次必须放行（issue #163 现场场景）", afterSecond)
        assertNull(
            "窗口内第 3 次必须拒绝，防 IDR 风暴（issue #70 / #55）",
            afterSecond!!.tryAcquire(310L),
        )
    }

    @Test
    fun `a refusal never refreshes the window`() {
        val afterTwo = SyncRequestGate().tryAcquire(0L)!!.tryAcquire(300L)!!
        // 被拒的请求不进入窗口，否则连续拒绝会把窗口无限续命，闸门永远打不开。
        for (t in listOf(400L, 500L, 999L)) {
            assertNull("t=${t}ms 时窗口已有 2 次放行，必须拒绝", afterTwo.tryAcquire(t))
        }
        assertNotNull(
            "t=1000ms 时 t=0 的记录滑出窗口，必须再次放行",
            afterTwo.tryAcquire(1000L),
        )
    }

    @Test
    fun `window slides by grant timestamps instead of a fixed interval`() {
        // 旧语义的「硬性 1s 间隔」在这里是反例：0ms 与 300ms 两次放行后，1000ms 只要求
        // 0ms 那条过期，而不是要求距 300ms 那条满 1s。
        val after1000 = SyncRequestGate().tryAcquire(0L)!!
            .tryAcquire(300L)!!
            .tryAcquire(1000L)
        assertNotNull(after1000)
        // 1000ms 与 1300ms 又占满窗口（1000ms 的记录与 300ms 的记录），1301ms 必须拒绝。
        val after1300 = after1000!!.tryAcquire(1300L)!!
        assertNull(after1300.tryAcquire(1301L))
        // 2000ms 时 1000ms 的记录过期，恢复一次放行额度。
        assertNotNull(after1300.tryAcquire(2000L))
    }

    @Test
    fun `observed 298ms secondary gap is granted instead of throttled`() {
        // issue #163 现场：2026-11-14 20:19:13.640 恢复（第 8 次），13.945 又断链，间隔 305ms。
        val first = SyncRequestGate().tryAcquire(20_000_000L)
        assertNotNull("第 8 次重同步的请求必须放行", first)
        assertNotNull(
            "298–305ms 后的二次缺口必须放行，否则车机要干等 3s 自然 GOP（冻结 2894ms 的成因）",
            first!!.tryAcquire(20_000_298L),
        )
    }

    @Test
    fun `both ends share one state machine and never diverge`() {
        // issue #75 的死区判据：同一初始状态、同一时间序列，车机与手机的放行/拒绝必须逐点
        // 一致（两端现在就是同一个 SyncRequestGate，任何一方私有实现都会在这里分叉）。
        var car = SyncRequestGate()
        var phone = SyncRequestGate()
        for (now in listOf(0L, 1L, 5L, 298L, 747L, 999L, 1000L, 1001L, 1400L, 2400L)) {
            val carNext = car.tryAcquire(now)
            val phoneNext = phone.tryAcquire(now)
            assertEquals(
                "t=${now}ms 两端决策不一致：车机=${carNext != null} 手机=${phoneNext != null} —— 死区回来了",
                carNext != null,
                phoneNext != null,
            )
            carNext?.let { car = it }
            phoneNext?.let { phone = it }
        }
    }
}
