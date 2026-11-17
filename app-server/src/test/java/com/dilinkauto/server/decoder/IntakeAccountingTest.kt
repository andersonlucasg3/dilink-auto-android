package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 收帧记账不变式（issue #205）。
 *
 * 现场 `keys_recv=123 keys_fed=118 keys_drop=0` 的根因是三个计数各自 `++`：冷启动补喂、
 * `stop()` 清队列等分支只收不记喂/丢。这里把「每帧入账一次、销账一次」钉死：
 *
 * ```
 * received == fed + dropped + pending
 * keyReceived == keyFed + keyDropped + keyPending
 * ```
 *
 * 不复制实现细节，只驱动四个入口后判等式。
 */
class IntakeAccountingTest {

    private fun balanced(accounting: IntakeAccounting) {
        val s = accounting.snapshot()
        assertEquals(
            "全量帧账不平：$s",
            s.received,
            s.fed + s.dropped + s.pending,
        )
        assertEquals(
            "关键帧账不平：$s",
            s.keyReceived,
            s.keyFed + s.keyDropped + s.keyPending,
        )
        assertTrue("pending 不得为负（同一帧被销账两次）：$s", s.pending >= 0L)
        assertTrue("keyPending 不得为负：$s", s.keyPending >= 0L)
    }

    @Test
    fun `随机收喂丢序列后不变式恒成立`() {
        val random = Random(205)
        val accounting = IntakeAccounting()
        // 维护一份「已入账未销账」的队列，随机地喂掉或丢掉队头，模拟真实帧流。
        val pendingKeys = ArrayDeque<Boolean>()

        repeat(5_000) {
            when (random.nextInt(3)) {
                0 -> {
                    val isKey = random.nextInt(10) == 0
                    accounting.onReceived(isKey)
                    pendingKeys.addLast(isKey)
                }
                1 -> if (pendingKeys.isNotEmpty()) {
                    accounting.onFed(pendingKeys.removeFirst())
                }
                else -> if (pendingKeys.isNotEmpty()) {
                    accounting.onDropped(pendingKeys.removeFirst())
                }
            }
            balanced(accounting)
        }
        // 随机游走结束时队列里可能还挂着帧：先按「丢弃」收尾，再断言账目归零。
        while (pendingKeys.isNotEmpty()) {
            accounting.onDropped(pendingKeys.removeFirst())
        }
        assertEquals(0L, accounting.snapshot().pending)
        assertEquals(0L, accounting.snapshot().keyPending)
        balanced(accounting)
    }

    @Test
    fun `同一帧重复销账会被 pending 下限抓出来`() {
        val accounting = IntakeAccounting()
        accounting.onReceived(isKey = false)
        accounting.onFed(isKey = false)
        assertNull("正常路径必须平账", accounting.violationText())

        // 一帧被既记喂入又记丢弃：等式本身仍成立（pending = -1 抹平），
        // 只有 pending 下限检查能抓到。
        accounting.onDropped(isKey = false)
        assertNotNull("重复销账必须被判为违例", accounting.violationText())
    }

    @Test
    fun `reset 后四项归零`() {
        val accounting = IntakeAccounting()
        accounting.onReceived(isKey = true)
        accounting.onFed(isKey = true)
        accounting.reset()
        val s = accounting.snapshot()
        assertEquals(0L, s.received)
        assertEquals(0L, s.fed)
        assertEquals(0L, s.dropped)
        assertEquals(0L, s.pending)
        assertEquals(0L, s.keyReceived)
        assertEquals(0L, s.keyFed)
        assertEquals(0L, s.keyDropped)
        assertEquals(0L, s.keyPending)
        assertTrue(s.balanced)
    }

    @Test
    fun `关键帧分项与全量分项互不串账`() {
        val accounting = IntakeAccounting()
        accounting.onReceived(isKey = true)
        accounting.onReceived(isKey = false)
        accounting.onFed(isKey = true)
        accounting.onDropped(isKey = false)
        val s = accounting.snapshot()
        assertEquals(2L, s.received)
        assertEquals(1L, s.fed)
        assertEquals(1L, s.dropped)
        assertEquals(1L, s.keyReceived)
        assertEquals(1L, s.keyFed)
        assertEquals(0L, s.keyDropped)
        balanced(accounting)
    }
}
