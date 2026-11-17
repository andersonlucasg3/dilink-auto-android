package com.dilinkauto.client.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

/**
 * AssetDeployer.extract / ensureCurrent 单测（通过 AssetSource seam，无需真 AssetManager）。
 * 这是手机上 vd-server.jar 的 CRC 新鲜度门；此前因 AssetManager 是 final 无法子类而完全不可测。
 */
class AssetDeployerExtractTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class FakeAssetSource(
        private val data: ByteArray,
        private val fail: Boolean = false
    ) : AssetSource {
        override fun read(assetName: String): ByteArray {
            if (fail) throw java.io.IOException("asset gone")
            return data
        }
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun deployer(data: ByteArray, fail: Boolean = false) =
        AssetDeployer(FakeAssetSource(data, fail))

    @Test
    fun `extract writes when no target exists`() {
        val data = "hello".toByteArray()
        val target = File(tmp.newFolder(), "vd-server.jar")
        val r = deployer(data).extract("vd-server.jar", target)
        assertTrue(r is AssetDeployer.Result.Written)
        r as AssetDeployer.Result.Written
        assertEquals(crc(data), r.crc)
        assertEquals(data.size, r.bytes)
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun `extract skips the write when the crc matches`() {
        val data = "engine-v2".toByteArray()
        val target = File(tmp.newFolder(), "vd-server.jar")
        target.writeBytes(data) // 预先落一份完全相同的
        val before = target.lastModified()
        val r = deployer(data).extract("vd-server.jar", target)
        assertTrue(r is AssetDeployer.Result.Current)
        assertEquals(crc(data), (r as AssetDeployer.Result.Current).crc)
        assertEquals("命中 CRC 不应重写", before, target.lastModified())
    }

    @Test
    fun `extract rewrites when a single byte differs`() {
        val data = ByteArray(64) { it.toByte() }
        val target = File(tmp.newFolder(), "vd-server.jar")
        val stale = data.copyOf().also { it[42] = (it[42].toInt() xor 1).toByte() }
        target.writeBytes(stale)
        val r = deployer(data).extract("vd-server.jar", target)
        assertTrue(r is AssetDeployer.Result.Written)
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun `extract creates missing parent directories`() {
        val data = "payload".toByteArray()
        val dir = File(tmp.newFolder(), "sdcard/DiLinkAuto") // 不存在
        val target = File(dir, "vd-server.jar")
        val r = deployer(data).extract("vd-server.jar", target)
        assertTrue(r is AssetDeployer.Result.Written)
        assertTrue(target.parentFile!!.isDirectory)
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun `extract reports a read failure and leaves the target untouched`() {
        val target = File(tmp.newFolder(), "vd-server.jar")
        target.writeBytes("old".toByteArray())
        val r = deployer(ByteArray(0), fail = true).extract("vd-server.jar", target)
        assertTrue(r is AssetDeployer.Result.Failed)
        assertTrue((r as AssetDeployer.Result.Failed).reason.startsWith("read:"))
        assertArrayEquals("old".toByteArray(), target.readBytes())
    }

    @Test
    fun `extract reports a write failure when the target is a directory`() {
        val data = "x".toByteArray()
        val asDir = tmp.newFolder("vd-server.jar") // 用一个目录占据目标路径
        val r = deployer(data).extract("vd-server.jar", asDir)
        assertTrue(r is AssetDeployer.Result.Failed)
        assertTrue((r as AssetDeployer.Result.Failed).reason.startsWith("write:"))
    }

    @Test
    fun `ensure current returns the crc of a fresh write`() {
        val data = "brand-new".toByteArray()
        val target = File(tmp.newFolder(), "vd-server.jar")
        assertEquals(crc(data), deployer(data).ensureCurrent("vd-server.jar", target))
    }

    @Test
    fun `ensure current returns crc without writing when already current`() {
        val data = "already-here".toByteArray()
        val target = File(tmp.newFolder(), "vd-server.jar")
        target.writeBytes(data)
        val before = target.lastModified()
        assertEquals(crc(data), deployer(data, fail = true).ensureCurrent("vd-server.jar", target)) // 读失败也无需重写
        assertEquals(before, target.lastModified())
    }

    @Test
    fun `ensure current falls back to the usable jar crc when the refresh fails`() {
        // A-L15：刷新失败但磁盘上已有可用 jar → 返回其 CRC，而不是 -1
        val data = "on-disk-usable".toByteArray()
        val target = File(tmp.newFolder(), "vd-server.jar")
        target.writeBytes(data)
        assertEquals(crc(data), deployer(ByteArray(0), fail = true).ensureCurrent("vd-server.jar", target))
    }

    @Test
    fun `ensure current returns minus one when nothing usable exists`() {
        val missing = File(tmp.newFolder(), "vd-server.jar")
        assertEquals(-1L, deployer(ByteArray(0), fail = true).ensureCurrent("vd-server.jar", missing))
    }

    @Test
    fun `ensure current returns minus one for an empty target`() {
        val empty = File(tmp.newFolder(), "vd-server.jar")
        empty.writeBytes(ByteArray(0))
        assertEquals(-1L, deployer(ByteArray(0), fail = true).ensureCurrent("vd-server.jar", empty))
    }

    @Test
    fun `the tmp sibling never survives any extract outcome`() {
        val data = "abcdef".toByteArray()

        // Written
        val t1 = File(tmp.newFolder(), "j.jar")
        deployer(data).extract("j.jar", t1)
        assertFalse(File("${t1.absolutePath}.tmp").exists())

        // Current
        val t2 = File(tmp.newFolder(), "j.jar"); t2.writeBytes(data)
        deployer(data).extract("j.jar", t2)
        assertFalse(File("${t2.absolutePath}.tmp").exists())

        // Failed(read)
        val t3 = File(tmp.newFolder(), "j.jar")
        deployer(data, fail = true).extract("j.jar", t3)
        assertFalse(File("${t3.absolutePath}.tmp").exists())

        // Failed(write) — target 是目录
        val t4 = tmp.newFolder("j.jar")
        deployer(data).extract("j.jar", t4)
        assertFalse(File("${t4.absolutePath}.tmp").exists())
    }
}
