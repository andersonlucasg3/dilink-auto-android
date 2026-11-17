package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CarAppInstaller.parseInstalledVersion 单测（从 readInstalledVersion 提取的纯函数）。
 * "0" 哨兵是"车机未装服务端"的唯一信号，正则匹配错行会导致漏装或无限重装。
 */
class CarAppInstallerParseVersionTest {

    @Test
    fun `parses version name from dumpsys output`() {
        assertEquals("0.17.0", CarAppInstaller.parseInstalledVersion("  versionName=0.17.0 (android...)\n"))
    }

    @Test
    fun `returns zero when dumpsys prints nothing`() {
        assertEquals("0", CarAppInstaller.parseInstalledVersion(""))
    }

    @Test
    fun `returns zero for an empty version value`() {
        assertEquals("0", CarAppInstaller.parseInstalledVersion("versionName=\n"))
    }

    @Test
    fun `takes the first match in the output`() {
        // 多个 versionName 行时取第一个（document first-match semantics）
        assertEquals("9.9.9", CarAppInstaller.parseInstalledVersion("versionName=9.9.9\nversionName=0.17.0"))
    }

    @Test
    fun `a version name cannot contain whitespace`() {
        assertEquals("0.17.0", CarAppInstaller.parseInstalledVersion("versionName=0.17.0 next=1"))
    }

    @Test
    fun `no version line yields the sentinel`() {
        assertEquals("0", CarAppInstaller.parseInstalledVersion("some unrelated output"))
    }
}
