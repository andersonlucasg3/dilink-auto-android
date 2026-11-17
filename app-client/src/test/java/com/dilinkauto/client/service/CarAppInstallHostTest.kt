package com.dilinkauto.client.service

import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CarAppInstallHostTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class FakeSession
    private class FakeInstaller : CarInstaller<FakeSession> {
        var connects = 0
        override fun connect(carIp: String): FakeSession? { connects++; return FakeSession() }
        override fun readInstalledVersion(session: FakeSession): String = "0"
        override fun pushAndInstall(session: FakeSession, apkFile: File, versionLabel: String): String = "Success"
        override fun close(session: FakeSession) {}
    }

    private class FakeCtx : ContextWrapper(null)

    @Test
    fun `rejects malformed explicit IP without launching install`() = runTest {
        val statuses = mutableListOf<String>()
        val host = CarAppInstallHost(
            context = FakeCtx(),
            scope = this,
            connectedCarIp = { null },
            onReinstalled = {},
            onStatus = { statuses += it },
            apkFile = tmp.newFile("app-server.apk"),
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        host.installCarApp("invalid-ip")

        assertTrue(statuses.any { it.contains("Invalid IP: invalid-ip") })
        assertFalse(host.installInFlight.get())
    }

    @Test
    fun `drops duplicate request when install is already in flight`() = runTest {
        val statuses = mutableListOf<String>()
        val host = CarAppInstallHost(
            context = FakeCtx(),
            scope = this,
            connectedCarIp = { null },
            onReinstalled = {},
            onStatus = { statuses += it },
            apkFile = tmp.newFile("app-server.apk"),
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        host.installInFlight.set(true)
        host.installCarApp("192.168.1.100")

        assertEquals(listOf("Install already in progress"), statuses)
    }

    @Test
    fun `runs install and clears in-flight flag upon completion`() = runTest {
        val statuses = mutableListOf<String>()
        val installer = FakeInstaller()
        val apk = tmp.newFile("app-server.apk")
        val coordinator = CarInstallCoordinator(
            context = FakeCtx(),
            apkFile = apk,
            installer = installer,
            connectedCarIp = { null },
            onReinstalled = {},
            status = { statuses += it },
            resolveCarIp = { "192.168.1.100" },
            myVersionName = { "1.0.0" }
        )

        var assetsReadyCalled = false
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val host = CarAppInstallHost(
            context = FakeCtx(),
            scope = this,
            connectedCarIp = { null },
            onReinstalled = {},
            ensureAssetsReady = { assetsReadyCalled = true },
            onStatus = { statuses += it },
            apkFile = apk,
            coordinator = coordinator,
            ioDispatcher = testDispatcher
        )

        host.installCarApp("192.168.1.100")
        testScheduler.advanceUntilIdle()

        assertTrue("ensureAssetsReady should have been called", assetsReadyCalled)
        assertEquals(1, installer.connects)
        assertFalse("installInFlight should be reset to false", host.installInFlight.get())
    }
}
