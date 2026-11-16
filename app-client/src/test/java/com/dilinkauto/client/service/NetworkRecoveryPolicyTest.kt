package com.dilinkauto.client.service

import com.dilinkauto.client.service.NetworkRecoveryPolicy.Decision
import com.dilinkauto.client.service.NetworkRecoveryPolicy.Link
import com.dilinkauto.client.service.NetworkRecoveryPolicy.Recheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #204：`Network lost` 进入 debounce 后再没有 `available`，但会话照常建立。
 *
 * 判据必须满足两件事：
 * 1. debounce 到期时若系统里仍有可用 Wi-Fi（回调没来），**必须**补一条 available；
 * 2. 复核确实不可用时保持 lost，原有断开流程不受影响（#200 的重建判据不动）。
 */
class NetworkRecoveryPolicyTest {

    private fun policy() = NetworkRecoveryPolicy()

    @Test
    fun `现场 16_36_37 lost 后复核仍有 Wi-Fi —— 必须补一条 available`() {
        val p = policy()
        p.onAvailable() // 16:36:09.248 available: 122
        assertTrue(p.onLost("122", nowMs = 0L)) // 16:36:37.801 lost: 122
        assertEquals(Link.LOST, p.state)

        assertEquals(
            Decision.REPORT_AVAILABLE,
            p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = "123")),
        )
        assertEquals(Link.AVAILABLE, p.state)
        assertNull(p.pendingLostNetId)
    }

    @Test
    fun `复核确认没有 Wi-Fi —— 保持 lost 并允许断开`() {
        val p = policy()
        p.onAvailable()
        p.onLost("122", nowMs = 0L)

        assertEquals(
            Decision.CONFIRM_LOST,
            p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = null)),
        )
        assertEquals("复核不可用时状态必须留在 lost", Link.LOST, p.state)
    }

    @Test
    fun `debounce 到期前回调补到 available —— 到期不再动作`() {
        val p = policy()
        p.onLost("122", nowMs = 0L)
        p.onAvailable() // 重关联成功，系统投递了 available

        assertEquals(
            Decision.NONE,
            p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = "122")),
        )
        assertEquals(Link.AVAILABLE, p.state)
    }

    @Test
    fun `debounce 未到期不得复核`() {
        val p = policy()
        p.onLost("122", nowMs = 0L)

        assertEquals(
            Decision.NONE,
            p.onDebounceExpired(nowMs = 2_999L, recheck = Recheck(wifiNetId = "122")),
        )
        assertEquals(Link.LOST, p.state)
    }

    @Test
    fun `同一 netId 重复 lost 不再重开 debounce`() {
        val p = policy()
        assertTrue(p.onLost("122", nowMs = 0L))
        assertFalse("重复 lost 不该重新计时（否则 3s 窗口被无限延长）", p.onLost("122", nowMs = 1_000L))
        assertEquals(
            Decision.REPORT_AVAILABLE,
            p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = "123")),
        )
    }

    @Test
    fun `不同 netId 的 lost 重新计时`() {
        val p = policy()
        p.onLost("122", nowMs = 0L)
        assertTrue(p.onLost("123", nowMs = 2_000L))
        assertTrue("新网络标识的窗口从 2000ms 起算", p.pendingLostNetId == "123")
        assertEquals(
            Decision.NONE,
            p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = null)),
        )
    }

    @Test
    fun `握手自证链路可用时把状态从 lost 拉回 available`() {
        val p = policy()
        p.onLost("122", nowMs = 0L)
        p.onDebounceExpired(nowMs = 3_000L, recheck = Recheck(wifiNetId = null))
        assertEquals(Link.LOST, p.state)

        // 方案 B：车机重新连上并完成握手，链路以真实流量自证可用
        assertTrue(p.onTrafficConfirmed())
        assertEquals(Link.AVAILABLE, p.state)
        assertNull(p.pendingLostNetId)
    }

    @Test
    fun `已经 available 时握手不再重复打日志`() {
        val p = policy()
        p.onAvailable()
        assertFalse(p.onTrafficConfirmed())
        assertEquals(Link.AVAILABLE, p.state)
    }
}
