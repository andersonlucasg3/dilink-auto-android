package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InstallStatus.parse / stageIndex / isInProgress / isTerminal 单测。
 * 这是状态字符串分类器，供安装卡切换 转圈/对勾/错误/重试。按英文子串匹配，故本地化串会漏判。
 */
class InstallStatusTest {

    @Test
    fun `empty and unrecognized are idle`() {
        assertEquals(InstallStatus.IDLE, InstallStatus.parse(""))
        assertEquals(-1, InstallStatus.stageIndex(""))
        assertEquals(InstallStatus.IDLE, InstallStatus.parse("something else"))
    }

    @Test
    fun `production status strings classify as expected`() {
        assertEquals(InstallStatus.SEARCHING, InstallStatus.parse("Searching for car"))
        assertEquals(InstallStatus.CONNECTING, InstallStatus.parse("Connecting to 192.168.1.5…"))
        assertEquals(InstallStatus.CHECKING, InstallStatus.parse("Checking version"))
        assertEquals(InstallStatus.PUSHING, InstallStatus.parse("Push APK (12MB)…"))
        assertEquals(InstallStatus.INSTALLING, InstallStatus.parse("Installing v0.17.0…"))
        assertEquals(InstallStatus.LAUNCHING, InstallStatus.parse("Launching car app…"))
        assertEquals(InstallStatus.DONE, InstallStatus.parse("Already up-to-date (v0.17.0)"))
        assertEquals(InstallStatus.DONE, InstallStatus.parse("Car app v0.17.0 installed!"))
        assertEquals(InstallStatus.AUTH_NEEDED, InstallStatus.parse("Authorization required — approve on the car"))
        assertEquals(InstallStatus.ERROR, InstallStatus.parse("Failed: boom"))
        assertEquals(InstallStatus.ERROR, InstallStatus.parse("Error: boom"))
        assertEquals(InstallStatus.ERROR, InstallStatus.parse("Car not found. Plug into USB or check WiFi ADB."))
    }

    @Test
    fun `success keyword wins over failure keywords in one string`() {
        // 现状：Success 先于 Error/Failed 判定
        assertEquals(InstallStatus.DONE, InstallStatus.parse("Failed: Success"))
    }

    @Test
    fun `hazard an unreachable ip is not classified as an error`() {
        // 期望 ERROR，实际因无关键词而落 IDLE（UI 会显示"可安装"而非重试）
        assertEquals(InstallStatus.IDLE, InstallStatus.parse("192.168.1.5 not reachable on port 5555"))
    }

    @Test
    fun `hazard an invalid ip is not classified as an error`() {
        assertEquals(InstallStatus.IDLE, InstallStatus.parse("Invalid IP: 1.2.3"))
    }

    @Test
    fun `hazard install already in progress reads as running work`() {
        // 期望终态拒绝；实际读成 INSTALLING + isInProgress，卡片会一直转圈
        assertEquals(InstallStatus.INSTALLING, InstallStatus.parse("Install already in progress"))
        assertTrue(InstallStatus.parse("Install already in progress").isInProgress)
    }

    @Test
    fun `is in progress covers exactly the stage states`() {
        val expected = listOf(
            InstallStatus.SEARCHING, InstallStatus.CONNECTING, InstallStatus.CHECKING,
            InstallStatus.PUSHING, InstallStatus.INSTALLING, InstallStatus.LAUNCHING
        )
        assertEquals(expected, InstallStatus.values().filter { it.isInProgress })
    }

    @Test
    fun `is terminal covers done error and auth needed`() {
        assertEquals(
            listOf(InstallStatus.AUTH_NEEDED, InstallStatus.DONE, InstallStatus.ERROR),
            InstallStatus.values().filter { it.isTerminal }
        )
    }

    @Test
    fun `stage index follows the last matching keyword`() {
        assertEquals(0, InstallStatus.stageIndex("Searching for car"))
        assertEquals(1, InstallStatus.stageIndex("Connecting to 10.0.0.5…"))
        assertEquals(2, InstallStatus.stageIndex("Checking version"))
        assertEquals(3, InstallStatus.stageIndex("Push APK (12MB)…"))
        assertEquals(4, InstallStatus.stageIndex("Installing v0.17.0…"))
        assertEquals(5, InstallStatus.stageIndex("Launching car app…"))
        assertEquals(-1, InstallStatus.stageIndex("Already up-to-date (v1)"))
        assertEquals(-1, InstallStatus.stageIndex("Failed"))
    }

    @Test
    fun `stage keywords are in execution order`() {
        assertEquals(
            listOf("Searching", "Connecting", "Checking", "Push", "Install", "Launching"),
            InstallStatus.stageKeywords.map { it.first }
        )
    }

    @Test
    fun `auth needed is the only attention terminal state`() {
        val s = InstallStatus.parse("Authorization required")
        assertEquals(InstallStatus.AUTH_NEEDED, s)
        assertTrue(s.isTerminal)
        assertFalse(s.isInProgress)
    }
}
