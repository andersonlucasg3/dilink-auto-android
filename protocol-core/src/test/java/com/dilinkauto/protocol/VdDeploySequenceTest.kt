package com.dilinkauto.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [vdRunDeploySequence] / [vdAwaitExit] convergence and ordering tests.
 *
 * Uses a fake executor (records commands, scripted probe results) plus a fake
 * clock so the 3s wait budget is spent instantly. The transport-level wrappers
 * are covered by `AdbDeployerTest` (desktop) against the same sequence.
 */
class VdDeploySequenceTest {

    private class FakeClock {
        var millis = 0L
        fun now(): Long = millis
        fun sleep(ms: Long) {
            millis += ms
        }
    }

    /** Records every command; probe results come from the scripted queue (default: GONE). */
    private class FakeExecutor(
        private val probes: ArrayDeque<VdProbeResult> = ArrayDeque(),
        private val launchResult: Boolean = true,
    ) : VdDeployExecutor {
        val commands = mutableListOf<String>()
        var probeCount = 0
            private set

        override suspend fun shellSync(command: String) {
            commands += command
        }

        override suspend fun launch(command: String): Boolean {
            commands += command
            return launchResult
        }

        override suspend fun probe(): VdProbeResult {
            probeCount++
            return probes.removeFirstOrNull() ?: VdProbeResult.GONE
        }
    }

    private fun plan(sessionToken: String = "") = VdDeploy.DeployPlan(
        args = "dummy-args",
        launchCommand = "launch-command",
        sessionToken = sessionToken,
    )

    private fun run(
        executor: FakeExecutor,
        clock: FakeClock = FakeClock(),
        plan: VdDeploy.DeployPlan = plan()
    ): VdDeployOutcome =
        runBlocking {
            vdRunDeploySequence(plan, executor, now = clock::now, sleep = { clock.sleep(it) })
        }

    /**
     * 假时钟下"graceful 预算 + 强杀确认窗口"的总耗时。
     *
     * 轮询循环的条件是 `now() < deadline`，所以预算会被
     * [VdDeploySequence.EXIT_WAIT_POLL_MS] **向上取整**（12s → 12.0s，10s → 10.05s）。
     * 从常量算而不是写死数字：预算一改，写死的断言会退化成"断言 13.05 秒"这种
     * 与被测行为无关的误导性失败。
     */
    private fun expectedStopElapsedMs(): Long {
        val poll = VdDeploySequence.EXIT_WAIT_POLL_MS
        val graceful = ((VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS + poll - 1) / poll) * poll
        return graceful + VdDeploySequence.EXIT_WAIT_TIMEOUT_MS
    }

    @Test
    fun `clean exit runs the any-session stop, two gone probes, then launch`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)))

        val outcome = run(executor)

        // 启动前置清场停的是"当前在跑的引擎"（token 未知），所以用读会话文件的命令；
        // 写本次 plan 的 token 会让新引擎启动后立刻读到自己的停止请求。
        assertEquals(listOf(VdDeploy.gracefulStopAnyCommand, "launch-command"), executor.commands)
        assertEquals("连续两次 GONE 确认退出", 2, executor.probeCount)
        assertFalse("正常退出不应触发兜底强杀", outcome.forcedKill)
        assertTrue(outcome.launched)
    }

    @Test
    fun `alive probe resets the consecutive-gone counter`() {
        val executor = FakeExecutor(
            ArrayDeque(listOf(VdProbeResult.ALIVE, VdProbeResult.GONE, VdProbeResult.GONE))
        )

        val outcome = run(executor)

        assertEquals("存活一次后需再确认两次", 3, executor.probeCount)
        assertFalse(outcome.forcedKill)
        assertEquals(listOf(VdDeploy.gracefulStopAnyCommand, "launch-command"), executor.commands)
    }

    @Test
    fun `graceful timeout falls back to force kill and still launches`() {
        // Never gone → both waits time out; the second must still end with launch.
        val commands = mutableListOf<String>()
        val stubborn = object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                commands += command
            }

            override suspend fun launch(command: String): Boolean {
                commands += command
                return true
            }

            override suspend fun probe(): VdProbeResult = VdProbeResult.ALIVE
        }
        val clock = FakeClock()
        val outcome = runBlocking {
            vdRunDeploySequence(plan(), stubborn, now = clock::now, sleep = { clock.sleep(it) })
        }

        assertTrue("graceful 超时后必须补 -9 兜底", outcome.forcedKill)
        assertEquals("两轮等待各耗尽预算", expectedStopElapsedMs(), clock.millis)
        assertEquals(
            listOf(VdDeploy.gracefulStopAnyCommand, VdDeploy.killCommandForce, "launch-command"),
            commands,
        )
    }

    @Test
    fun `unknown probe accepts the exit immediately`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.UNKNOWN)))

        val outcome = run(executor)

        assertEquals("无法探测时立即放行，不轮询", 1, executor.probeCount)
        assertFalse(outcome.forcedKill)
        assertEquals(listOf(VdDeploy.gracefulStopAnyCommand, "launch-command"), executor.commands)
    }

    @Test
    fun `launch failure is propagated without force-kill`() {
        val executor = FakeExecutor(
            ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)),
            launchResult = false,
        )

        val outcome = run(executor)

        assertFalse(outcome.launched)
        assertFalse(outcome.forcedKill)
    }

    // ── vdStopEngine（会话收尾专用：只停不启，2026-10-10）──

    @Test
    fun `stop engine writes the sentinel and waits, without launching`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)))
        val clock = FakeClock()

        val forcedKill = runBlocking {
            vdStopEngine(executor, now = clock::now, sleep = { clock.sleep(it) })
        }

        assertFalse("干净退出不应触发强杀", forcedKill)
        assertEquals("收尾路径只停不启", listOf(VdDeploy.gracefulStopCommand()), executor.commands)
        assertEquals(2, executor.probeCount)
    }

    /** issue #180 第三版：收尾必须用调用方记住的 token，写本会话专属哨兵。 */
    @Test
    fun `stop engine writes the caller's own session sentinel`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)))
        val clock = FakeClock()

        runBlocking {
            vdStopEngine(executor, sessionToken = "T-self", now = clock::now, sleep = { clock.sleep(it) })
        }

        assertEquals(
            listOf(VdDeploy.gracefulStopCommand("T-self")),
            executor.commands,
        )
        assertTrue(
            "必须写到本会话专属文件: ${executor.commands}",
            executor.commands.single().contains(VdDeploy.stopRequestPath("T-self"))
        )
    }

    /** 部署序列的 outcome 必须带回本次会话 token，供调用方收尾使用。 */
    @Test
    fun `deploy outcome carries the session token`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)))

        val outcome = run(executor, plan = plan(sessionToken = "T-new"))

        assertEquals("T-new", outcome.sessionToken)
    }

    @Test
    fun `stop engine force kills when the graceful wait times out`() {
        val stubborn = object : VdDeployExecutor {
            val commands = mutableListOf<String>()
            override suspend fun shellSync(command: String) {
                commands += command
            }

            override suspend fun launch(command: String): Boolean = true

            override suspend fun probe(): VdProbeResult = VdProbeResult.ALIVE
        }
        val clock = FakeClock()

        val forcedKill = runBlocking {
            vdStopEngine(stubborn, now = clock::now, sleep = { clock.sleep(it) })
        }

        assertTrue(forcedKill)
        assertEquals(
            listOf(VdDeploy.gracefulStopCommand(), VdDeploy.killCommandForce),
            stubborn.commands,
        )
        assertEquals("两轮等待各耗尽预算", expectedStopElapsedMs(), clock.millis)
    }
}
