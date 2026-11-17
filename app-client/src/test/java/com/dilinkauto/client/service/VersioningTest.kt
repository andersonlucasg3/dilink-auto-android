package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versioning（parseVersion / compareVersions）单测：车端 APK 安装门槛。
 * 比较错 → 要么车机永远跑旧引擎，要么每次点击都重推 20MB。
 */
class VersioningTest {

    @Test
    fun `parse release version`() {
        val p = parseVersion("0.17.0")
        assertEquals("0.17.0", p.base); assertFalse(p.isDev); assertEquals(0, p.devNum)
    }

    @Test
    fun `parse bare dev suffix`() {
        val p = parseVersion("0.17.0-dev")
        assertEquals("0.17.0", p.base); assertTrue(p.isDev); assertEquals(0, p.devNum)
    }

    @Test
    fun `parse numbered dev suffix`() {
        val p = parseVersion("0.17.0-dev-02")
        assertEquals("0.17.0", p.base); assertTrue(p.isDev); assertEquals(2, p.devNum)
    }

    @Test
    fun `dev number is decimal not octal`() {
        assertEquals(10, parseVersion("1.0.0-dev-010").devNum)
    }

    @Test
    fun `snapshot suffix is not a dev build`() {
        val p = parseVersion("0.17.0-SNAPSHOT")
        assertEquals("0.17.0-SNAPSHOT", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `non numeric dev number is not a dev build`() {
        val p = parseVersion("1.0.0-dev-abc")
        assertEquals("1.0.0-dev-abc", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `greedy base takes the last dev marker`() {
        val p = parseVersion("1.0.0-dev-dev-3")
        assertEquals("1.0.0-dev", p.base); assertTrue(p.isDev); assertEquals(3, p.devNum)
    }

    @Test
    fun `uppercase dev suffix is not recognized`() {
        val p = parseVersion("1.0.0-DEV-3")
        assertEquals("1.0.0-DEV-3", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `compare orders numerically not lexically`() {
        assertTrue(compareVersions("0.9.0", "0.10.0") < 0)
    }

    @Test
    fun `compare treats missing components as zero`() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(0, compareVersions("1", "1.0.0"))
    }

    @Test
    fun `compare coerces non numeric components to zero`() {
        assertEquals(0, compareVersions("1.2.beta", "1.2.0"))
    }

    @Test
    fun `compare ignores leading zeros`() {
        assertEquals(0, compareVersions("01.002.3", "1.2.3"))
    }

    @Test
    fun `release beats dev of the same base`() {
        assertTrue(compareVersions("0.17.0", "0.17.0-dev-99") > 0)
    }

    @Test
    fun `dev builds order by number`() {
        assertTrue(compareVersions("0.17.0-dev-02", "0.17.0-dev-01") > 0)
        assertTrue(compareVersions("0.17.0-dev", "0.17.0-dev-01") < 0)
    }

    @Test
    fun `dev of a newer base beats an older release`() {
        assertTrue(compareVersions("0.18.0-dev", "0.17.0") > 0)
    }

    @Test
    fun `empty string behaves like zero base`() {
        assertEquals(0, compareVersions("", "0.0.0"))
        assertTrue(compareVersions("", "0.0.1") < 0)
    }

    @Test
    fun `compare is irreflexive and antisymmetric`() {
        val samples = listOf("0.17.0", "0.17.0-dev", "0.17.0-dev-02", "1.2.beta", "")
        for (v in samples) assertEquals(0, compareVersions(v, v))
        for (a in samples) for (b in samples) {
            assertEquals(
                Integer.signum(compareVersions(a, b)),
                -Integer.signum(compareVersions(b, a))
            )
        }
    }

    @Test
    fun `hazard a snapshot compares equal to its release`() {
        // 固定现状：-SNAPSHOT 的最后一段 coerce 成 0，与 release 判定相等
        assertEquals(0, compareVersions("0.17.0-SNAPSHOT", "0.17.0"))
    }

    @Test
    fun `hazard a plain zero version never installs over the not installed sentinel`() {
        // CarAppInstaller.readInstalledVersion 无安装时返回 "0"；versionName 为 "0"/"0.0"/"0.0.0" 会被判为"已最新"
        assertEquals(0, compareVersions("0.0.0", "0"))
        assertEquals(0, compareVersions("0", "0"))
    }

    // ---- 日期-时间版本号（issue #224）----
    // 版本号切换成 YYYYMMDD-HHMM 后，旧实现把整串当一个非数字段 coerce 成 0，
    // 任意两个日期版本号都判相等 → CarInstallCoordinator 的 `<= 0` 分支永久跳过安装。

    @Test
    fun `date version newer day beats older day`() {
        assertTrue(compareVersions("20261118-0900", "20261117-2202") > 0)
    }

    @Test
    fun `date version newer time beats older time same day`() {
        assertTrue(compareVersions("20261117-2300", "20261117-2202") > 0)
        assertTrue(compareVersions("20261117-0900", "20261117-2202") < 0)
    }

    @Test
    fun `date version equal to itself`() {
        assertEquals(0, compareVersions("20261117-2202", "20261117-2202"))
    }

    @Test
    fun `date version beats any semantic version`() {
        // 从 1.0.3 升级到日期版本号的那一次，车机必须被判为「需要安装」
        assertTrue(compareVersions("20261117-2202", "1.0.3") > 0)
        assertTrue(compareVersions("1.0.3", "20261117-2202") < 0)
    }

    @Test
    fun `date version ordering is transitive across the year boundary`() {
        assertTrue(compareVersions("20270101-0000", "20261231-2359") > 0)
        assertTrue(compareVersions("20261231-2359", "20260101-0000") > 0)
    }

    @Test
    fun `date version parses into day and minute segments`() {
        val p = parseVersion("20261117-2202")
        assertEquals("20261117-2202", p.base)
        assertFalse(p.isDev)
        assertEquals(0, p.devNum)
    }

    @Test
    fun `date version compare is irreflexive and antisymmetric`() {
        val samples = listOf("20261117-2202", "20261118-0900", "1.0.3", "20261117-2202-dev-1")
        for (v in samples) assertEquals(0, compareVersions(v, v))
        for (a in samples) for (b in samples) {
            assertEquals(
                Integer.signum(compareVersions(a, b)),
                -Integer.signum(compareVersions(b, a))
            )
        }
    }

    @Test
    fun `date version still honours a dev suffix`() {
        // 日期格式确实会走 -dev 分支：base 是日期串，dev 排在同日期的 release 之后。
        // 这一例不鼓励发 `-dev` 日期包（日常迭代直接用新时间戳），只是把行为钉住，
        // 免得有人以为「日期格式不支持 dev」而写出错误的兼容逻辑。
        val p = parseVersion("20261117-2247-dev-1")
        assertEquals("20261117-2247", p.base)
        assertTrue(p.isDev)
        assertEquals(1, p.devNum)
        assertTrue(compareVersions("20261117-2247", "20261117-2247-dev-1") > 0)
        assertTrue(compareVersions("20261118-0900", "20261117-2247-dev-1") > 0)
    }
}
