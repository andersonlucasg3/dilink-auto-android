package com.dilinkauto.client.service

import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShizukuVdDeployerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `deploy runs sequence with custom executor and succeeds`() = runBlocking {
        val calls = mutableListOf<String>()
        val fakeExecutor = object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                calls.add("shellSync:$command")
            }

            override suspend fun launch(command: String): Boolean {
                calls.add("launch:$command")
                return true
            }

            override suspend fun probe(): VdProbeResult {
                calls.add("probe")
                return VdProbeResult.GONE
            }
        }

        val testDir = tmp.newFolder("shizuku_vd")
        val jarPath = "${testDir.absolutePath}/vd-server.jar"
        val logPath = "${testDir.absolutePath}/vd-server.log"

        val outcome = ShizukuVdDeployer.deploy(
            carWidth = 1920,
            carHeight = 1080,
            vdWidth = 1920,
            vdHeight = 1080,
            dpi = 240,
            targetFps = 30,
            targetBitrate = 4000000,
            carHost = "192.168.1.100",
            allowedPackages = listOf("com.example.app"),
            jarPath = jarPath,
            logPath = logPath,
            dirPath = testDir.absolutePath,
            executorOverride = fakeExecutor
        )

        assertNotNull(outcome)
        assertTrue(outcome!!.launched)
        assertTrue(calls.any { it.startsWith("launch:") })
    }
}
