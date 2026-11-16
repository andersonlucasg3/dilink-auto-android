package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车机日志回传积压的上报口径（issue #206）。
 *
 * 实车 `client.log:386`：外层手机时间 `[16:36:15.996]` 与车机内嵌时间 `[16:36:09.378]`
 * 差 6.6s；同一时刻 40ms 内涌入 49 行 —— 排错时会把 6 秒前的因果当成当前因果。
 * 这里钉住「超过 1s 才报、每 5s 最多一行、行内有队列深度与延迟」三条规则。
 *
 * 时钟由构造参数注入，所以不需要 Robolectric（`app-server` 本来就没有）。
 */
class CarLogPolicyTest {

    @Test
    fun `backlog line is the frozen greppable contract`() {
        // 现场用 `grep 'CarLog backlog'` 定位积压时段
        assertEquals("CarLog backlog=37 delay=6618ms", carLogBacklogLine(37, 6618))
    }

    @Test
    fun `only lines delayed beyond one second are reported`() {
        var now = 0L
        val reporter = CarLogBacklogReporter({ now })
        assertNull(reporter.onDrained(999, 5))
        assertEquals("CarLog backlog=5 delay=1000ms", reporter.onDrained(1000, 5))
    }

    @Test
    fun `reports at most once per five seconds`() {
        var now = 10_000L
        val reporter = CarLogBacklogReporter({ now })
        assertEquals("CarLog backlog=49 delay=6618ms", reporter.onDrained(6618, 49))

        now += 4_999
        assertNull(reporter.onDrained(7_000, 60))

        now += 1
        assertEquals("CarLog backlog=60 delay=9000ms", reporter.onDrained(9_000, 60))
    }

    @Test
    fun `below-threshold lines do not consume the report window`() {
        var now = 0L
        val reporter = CarLogBacklogReporter({ now })
        assertEquals("CarLog backlog=3 delay=1200ms", reporter.onDrained(1_200, 3))

        // 中间这些没超阈值的行不该把下一次真积压的告警挤掉
        now += 2_000
        assertNull(reporter.onDrained(20, 3))

        now += 3_000
        assertEquals("CarLog backlog=4 delay=1500ms", reporter.onDrained(1_500, 4))
    }

    @Test
    fun `every report reflects the backlog at drain time`() {
        var now = 0L
        val reporter = CarLogBacklogReporter({ now })
        assertEquals("CarLog backlog=1 delay=1001ms", reporter.onDrained(1_001, 1))
        now += 5_000
        assertEquals("CarLog backlog=2048 delay=2000ms", reporter.onDrained(2_000, 2_048))
    }
}
