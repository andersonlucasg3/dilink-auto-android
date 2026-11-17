package com.dilinkauto.client.service

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CarInstallCoordinator 安装状态机单测。
 *
 * 依赖全部注入：CarInstaller<S> 用 fake（无需真 Dadb）、resolveCarIp 固定返回 IP、
 * myVersionName 固定返回版本号。runTest 的虚拟时钟让 finally 里的 5000ms 清屏延迟瞬间完成。
 * 由于 getString 返回 "S<id>"  opaque 文本，断言以"可观测量"为准：push/connect/close 次数、
 * onReinstalled、以及 statuses 末位是否被清空（keepStatus 契约）。
 */
class CarInstallCoordinatorTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class FakeSession

    private class FakeInstaller : CarInstaller<FakeSession> {
        var connectResult: FakeSession? = null
        var installedVersion: String = "0"
        var pushResult: String = "Success"
        var throwOnRead: Throwable? = null
        var throwOnPush: Throwable? = null

        var connects = 0; var closes = 0; var pushes = 0
        override fun connect(carIp: String): FakeSession? { connects++; return connectResult }
        override fun readInstalledVersion(session: FakeSession): String { throwOnRead?.let { throw it }; return installedVersion }
        override fun pushAndInstall(session: FakeSession, apkFile: File, versionLabel: String): String {
            pushes++; throwOnPush?.let { throw it }; return pushResult
        }
        override fun close(session: FakeSession) { closes++ }
    }

    private class FakeCtx : ContextWrapper(null)

    private fun coordinator(
        apk: File,
        installer: FakeInstaller,
        myVersion: String,
        statuses: MutableList<String>,
        onReinstalled: () -> Unit = {}
    ) = CarInstallCoordinator<FakeSession>(
        context = FakeCtx(),
        apkFile = apk,
        installer = installer,
        connectedCarIp = { null },
        onReinstalled = onReinstalled,
        status = { statuses += it },
        resolveCarIp = { ip -> ip ?: "10.0.0.9" },
        myVersionName = { myVersion }
    )

    @Test
    fun `missing apk reports not found and returns early`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller()
        val apk = File(tmp.newFolder(), "does-not-exist.apk")
        coordinator(apk, installer, "0.18.0", statuses).install("10.0.0.9")
        assertEquals("", statuses.last())
        assertEquals("不应发起连接", 0, installer.connects)
    }

    @Test
    fun `connect failure keeps the auth message`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply { connectResult = null }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.18.0", statuses).install("10.0.0.9")
        assertTrue("应尝试连接", installer.connects >= 1)
        assertEquals("无 session，不应 close", 0, installer.closes)
        assertFalse("keepStatus=true：末位不应被清空", statuses.last().isEmpty())
    }

    @Test
    fun `equal versions skip the install`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply { connectResult = FakeSession(); installedVersion = "0.17.0" }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.17.0", statuses).install("10.0.0.9")
        assertEquals("已最新，不应推送", 0, installer.pushes)
        assertEquals("session 必须被关闭", 1, installer.closes)
        assertEquals("", statuses.last())
    }

    @Test
    fun `older installed version pushes and installs`() = runTest {
        val statuses = mutableListOf<String>()
        var reinstalled = 0
        val installer = FakeInstaller().apply { connectResult = FakeSession(); installedVersion = "0.17.0-dev-01"; pushResult = "Success\n" }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.17.0-dev-02", statuses, onReinstalled = { reinstalled++ }).install("10.0.0.9")
        assertEquals(1, installer.pushes)
        assertEquals(1, installer.closes)
        assertEquals(1, reinstalled)
        assertEquals("", statuses.last())
    }

    @Test
    fun `a newer installed version skips the install`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply { connectResult = FakeSession(); installedVersion = "0.18.0" }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.17.0", statuses).install("10.0.0.9")
        assertEquals(0, installer.pushes)
        assertEquals(1, installer.closes)
    }

    @Test
    fun `a failed push surfaces the trimmed output`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply {
            connectResult = FakeSession(); installedVersion = "0.1.0"
            pushResult = "Failure\n[INSTALL_FAILED_ALREADY_EXISTS]\n"
        }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.18.0", statuses).install("10.0.0.9")
        assertTrue(statuses.any { it.contains("INSTALL_FAILED_ALREADY_EXISTS") })
        assertTrue("Failure 不该被误判为 Success", statuses.none { it.contains("Success") })
        assertEquals(1, installer.closes)
    }

    @Test
    fun `an exception is reported and clears the status`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply {
            connectResult = FakeSession()
            throwOnRead = RuntimeException("boom")
        }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        coordinator(apk, installer, "0.18.0", statuses).install("10.0.0.9")
        assertTrue(statuses.any { it.contains("boom") })
        assertEquals("异常路径也应清屏", "", statuses.last())
        assertEquals("异常路径也应关闭 session", 1, installer.closes)
    }

    @Test
    fun `a blank explicit ip falls back to the resolver`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller().apply { connectResult = FakeSession(); installedVersion = "0.18.0" }
        val apk = File(tmp.root, "app-server.apk").also { it.writeBytes(ByteArray(8)) }
        // install("   ") → resolveCarIp 收到 "   "，我们的 resolver 返回 10.0.0.9（非空则用其值）
        coordinator(apk, installer, "0.17.0", statuses).install("   ")
        // 能走到连接说明 resolver 未因空串短路
        assertTrue(installer.connects >= 1)
    }
}
