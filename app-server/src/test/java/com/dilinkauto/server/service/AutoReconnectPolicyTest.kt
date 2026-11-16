package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #201 的纯函数钉子：[AutoReconnectPolicy.decide] 必须让 `userDisconnected`
 * 在**每一个**自动入口都生效，同时不得误伤用户主动发起的连接。
 *
 * 表驱动覆盖全部 [ConnectTrigger]：以后新增入口时，只要它出现在枚举里，就会被这组用例
 * 强制要求遵守同一套语义（否则这里先红）。
 */
class AutoReconnectPolicyTest {

    private val allTriggers = ConnectTrigger.values().toList()
    private val autoTriggers = allTriggers.filter { it.auto }
    private val userTriggers = allTriggers.filterNot { it.auto }

    @Test
    fun `both trigger classes are non-empty`() {
        assertFalse("必须存在自动入口，否则闸门形同虚设", autoTriggers.isEmpty())
        assertFalse("必须存在用户入口，否则断开后无法恢复", userTriggers.isEmpty())
        assertEquals(
            "自动入口清单与 issue #201 的枚举一致（ADB / 服务 START / 重试 / 网络 / 网关探测）",
            setOf(
                ConnectTrigger.AUTO_ADB_READY,
                ConnectTrigger.AUTO_SERVICE_START,
                ConnectTrigger.AUTO_RETRY,
                ConnectTrigger.AUTO_NETWORK_AVAILABLE,
                ConnectTrigger.AUTO_DISCOVERY,
            ),
            autoTriggers.toSet(),
        )
    }

    @Test
    fun `every auto trigger is suppressed while the user disconnect flag is set`() {
        for (trigger in autoTriggers) {
            val decision = AutoReconnectPolicy.decide(trigger, userDisconnected = true, inCooldown = false)
            assertFalse("userDisconnected=true 时 ${trigger.label} 不得发起连接（issue #201）", decision.allowed)
            assertEquals(
                "${trigger.label} 的抑制原因必须是 userDisconnected",
                ReconnectSuppressed.USER_DISCONNECTED,
                decision.suppressed,
            )
            assertFalse("自动入口永远不许清除 userDisconnected", decision.clearUserDisconnected)
        }
    }

    @Test
    fun `every auto trigger is suppressed during the post-disconnect cooldown`() {
        for (trigger in autoTriggers) {
            val decision = AutoReconnectPolicy.decide(trigger, userDisconnected = false, inCooldown = true)
            assertFalse("冷却期内 ${trigger.label} 不得发起连接（issue #201 候选 B）", decision.allowed)
            assertEquals(
                "${trigger.label} 的抑制原因必须是 cooldown",
                ReconnectSuppressed.COOLDOWN,
                decision.suppressed,
            )
            assertFalse(decision.clearUserDisconnected)
        }
    }

    @Test
    fun `every auto trigger passes with a clean slate`() {
        for (trigger in autoTriggers) {
            val decision = AutoReconnectPolicy.decide(trigger, userDisconnected = false, inCooldown = false)
            assertTrue("正常状态下 ${trigger.label} 必须放行", decision.allowed)
            assertNull("放行时不应带抑制原因", decision.suppressed)
            assertFalse("自动入口不得改写 userDisconnected", decision.clearUserDisconnected)
        }
    }

    @Test
    fun `user triggers always pass and clear the flag`() {
        for (trigger in userTriggers) {
            for (flag in listOf(false, true)) {
                for (cooldown in listOf(false, true)) {
                    val decision = AutoReconnectPolicy.decide(trigger, userDisconnected = flag, inCooldown = cooldown)
                    assertTrue(
                        "用户入口 ${trigger.label} 不能被 userDisconnected=$flag / cooldown=$cooldown 拦住" +
                            "（否则按钮看起来坏了）",
                        decision.allowed,
                    )
                    assertTrue("用户入口 ${trigger.label} 必须清除 userDisconnected", decision.clearUserDisconnected)
                    assertNull(decision.suppressed)
                }
            }
        }
    }

    @Test
    fun `suppression reasons carry grep-able log text`() {
        for (reason in ReconnectSuppressed.values()) {
            assertNotNull(reason.logText)
            assertTrue("抑制原因日志文本不能为空", reason.logText.isNotBlank())
        }
        assertEquals("userDisconnected", ReconnectSuppressed.USER_DISCONNECTED.logText)
    }

    @Test
    fun `cooldown covers the observed 3 to 13 second reconnect window`() {
        assertTrue(
            "冷却窗口必须覆盖实车观测到的 3~13s（issue #201 证据链），当前 " +
                "${AutoReconnectPolicy.USER_DISCONNECT_COOLDOWN_MS}ms",
            AutoReconnectPolicy.USER_DISCONNECT_COOLDOWN_MS >= 15_000L,
        )
    }
}
