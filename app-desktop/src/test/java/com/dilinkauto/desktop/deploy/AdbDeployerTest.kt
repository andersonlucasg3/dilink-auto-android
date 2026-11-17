package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.VdDeploy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AdbDeployer] 命令序列单测。
 *
 * 注入假 [AdbRunner] + 假时钟：CI 上不会装 adb，也不该起真机进程；同时假时钟让
 * "等旧引擎退出"这段真实要 3 秒的轮询在测试里瞬时完成。
 *
 * [AdbDeployer.deploy] 是挂起函数（audit D-M6：部署必须可取消、且用 [runBlocking]
 * 驱动而不是内部再包一层 runBlocking），所以用例统一在 `runBlocking` 里调。
 */
class AdbDeployerTest {

    private class FakeTime {
        var millis = 0L
        fun now(): Long = millis
        fun sleep(ms: Long) {
            millis += ms
        }
    }

    /** 记录每条 adb 命令，并按命令种类返回可配置结果。 */
    private class FakeRunner(
        private val connectResult: Boolean = true,
        private val launchResult: Boolean = true,
        /** 传给探活命令的"引擎是否存活"判定。 */
        private val probeAlive: () -> Boolean = { false },
    ) : AdbRunner {
        data class Call(val args: List<String>, val waitForExit: Boolean, val timeoutMs: Long)

        val calls = CopyOnWriteArrayList<Call>()
        var killAllCalled = 0
            private set

        override fun run(
            args: List<String>,
            waitForExit: Boolean,
            timeoutMs: Long,
            onOutput: (String) -> Unit,
        ): Boolean {
            calls += Call(args, waitForExit, timeoutMs)
            val command = args.getOrNull(3)
            return when {
                args.firstOrNull() == "connect" -> connectResult
                // 停止命令有两份：启动前置清场（读会话文件）与会话收尾（写自己那份）。
                command == VdDeploy.gracefulStopAnyCommand -> true
                command?.startsWith("p=") == true -> true
                command == VdDeploy.probeExitCodeCommand -> probeAlive()
                command == VdDeploy.killCommandForce -> true
                args.lastOrNull()?.contains("app_process") == true -> launchResult
                else -> true
            }
        }

        override fun killAll() {
            killAllCalled++
        }

        /** 所有 `shell` 命令的命令体（去掉了 `-s <serial> shell` 外壳）。 */
        fun shellCommands(): List<String> =
            calls.filter { it.args.getOrNull(2) == "shell" }.map { it.args[3] }

        fun probeCalls(): Int = shellCommands().count { it == VdDeploy.probeExitCodeCommand }

        fun launchCall(): Call = calls.last { it.args.last().contains("app_process") }
    }

    private fun config() = DesktopConfig(
        phoneHost = "192.168.3.206",
        viewportWidth = 1280,
        viewportHeight = 720,
    )

    private fun deployer(runner: FakeRunner, time: FakeTime = FakeTime()) =
        AdbDeployer(runner = runner, now = { time.now() }, sleep = { time.sleep(it) })

    @Test
    fun `正常流程按 connect - 优雅停止 - 探活 - 前台启动 的顺序执行`() = runBlocking {
        val runner = FakeRunner()
        val logs = mutableListOf<String>()

        val ok = deployer(runner).deploy(config(), adbPort = 5555, jarPath = VdDeploy.JAR_PATH) { logs.add(it) }

        assertTrue(ok)
        val shells = runner.shellCommands()
        // 启动前置清场（读设备会话文件定 token）→ 探活 ×2（连续两次"已退出"） → launch
        assertEquals(listOf(VdDeploy.gracefulStopAnyCommand, VdDeploy.probeExitCodeCommand, VdDeploy.probeExitCodeCommand), shells.dropLast(1))
        assertTrue("最后一条应是启动命令: ${shells.last()}", shells.last().contains("exec app_process"))

        // 启动命令必须保持前台（waitForExit=false），否则 adb shell 流一关引擎就被回收
        assertFalse("启动必须异步保持附着", runner.launchCall().waitForExit)
        assertEquals(listOf("connect", "192.168.3.206:5555"), runner.calls.first().args)
    }

    @Test
    fun `connect 失败即中止，不会去 kill 或启动`() = runBlocking {
        val runner = FakeRunner(connectResult = false)

        val ok = deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertFalse(ok)
        assertEquals(1, runner.calls.size)
        assertEquals(listOf("connect", "192.168.3.206:5555"), runner.calls.single().args)
    }

    @Test
    fun `必须连续两次判定退出才认账`() = runBlocking {
        // 第一次探活仍报存活（上一次传输抖动/进程正在退出），后两次才报已退出
        val probes = ArrayDeque(listOf(true, false, false))
        val runner = FakeRunner(probeAlive = { probes.removeFirstOrNull() ?: false })

        deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertEquals("应探活 3 次（存活→退出→退出）", 3, runner.probeCalls())
    }

    @Test
    fun `旧引擎迟迟不退时会补一次强制 kill 再启动`() = runBlocking {
        // 探活永远报存活 → graceful 等待超时 → 走 -9 兜底
        val runner = FakeRunner(probeAlive = { true })

        val ok = deployer(runner).deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        assertTrue(ok)
        assertTrue("应补发 killCommandForce: ${runner.shellCommands()}", runner.shellCommands().contains(VdDeploy.killCommandForce))
        // 无论等多久，最后一定是启动命令，不能把会话卡在"等旧引擎退出"
        assertTrue(runner.shellCommands().last().contains("exec app_process"))
    }

    @Test
    fun `close 会杀掉所有挂着的长驻进程`() {
        val runner = FakeRunner()
        deployer(runner).close()
        assertEquals(1, runner.killAllCalled)
    }

    /**
     * 收尾路径（2026-10-10）：关窗 / 「应用并重连」时在 `close()`（杀 adb 锚点）
     * 之前必须先让设备侧引擎优雅退出 —— 否则锚点一死引擎被 adbd 回收，
     * `cleanup()` 被截断（面板熄、screen_off_timeout 停在哨兵值）。
     */
    @Test
    fun `gracefulStop 未部署过时是 no-op`() = runBlocking {
        val runner = FakeRunner()

        val stopped = deployer(runner).gracefulStop {}

        assertFalse("本端没部署过引擎（Shizuku 路径），不应发任何命令", stopped)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `gracefulStop 发哨兵并等两次 GONE，不启动新引擎`() = runBlocking {
        val runner = FakeRunner()
        val deployer = deployer(runner)
        deployer.deploy(config(), 5555, VdDeploy.JAR_PATH) {} // 先部署一次以记录 serial

        val stopped = deployer.gracefulStop {}

        assertTrue(stopped)
        // 收尾阶段 = 启动命令（app_process）之后的全部 shell 命令。
        // 收尾哨兵必须用**本次部署**的 token（不是回读设备）—— issue #180 第三版。
        val token = deployer.lastSessionToken
        assertTrue("部署必须记住会话 token: $token", !token.isNullOrBlank())
        val teardownShells = runner.shellCommands().dropWhile { !it.contains("app_process") }.drop(1)
        assertEquals(
            listOf(VdDeploy.gracefulStopCommand(token), VdDeploy.probeExitCodeCommand, VdDeploy.probeExitCodeCommand),
            teardownShells,
        )
    }

    @Test
    fun `gracefulStop 引擎不退时补 -9 兜底`() = runBlocking {
        val runner = FakeRunner(probeAlive = { true })
        val deployer = deployer(runner)
        deployer.deploy(config(), 5555, VdDeploy.JAR_PATH) {}

        val stopped = deployer.gracefulStop {}

        assertTrue(stopped)
        assertTrue(
            "应补发 killCommandForce: ${runner.shellCommands()}",
            runner.shellCommands().contains(VdDeploy.killCommandForce),
        )
    }

    /**
     * D-M6：两轮部署必须串行。
     *
     * "会话中途 restart"时旧的部署协程可能还在对**共享的** runner 跑命令 —— 旧协程
     * 杀 adb 进程 / 新协程已经起了自己的进程，两个引擎同时抢 9638/9639。Mutex 让
     * 同一时刻只有一轮部署在跑。
     */
    @Test
    fun `并发部署被串行化`() = runBlocking {
        val runner = FakeRunner()
        val overlap = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOverlap = java.util.concurrent.atomic.AtomicInteger(0)
        val deployer = AdbDeployer(
            runner = runner,
            now = { 0L },
            sleep = { ms ->
                // 在"等待"里检测并发：只要有两轮部署同时在 sleep，就说明没串起来
                overlap.incrementAndGet()
                maxOverlap.updateAndGet { maxOf(it, overlap.get()) }
                kotlinx.coroutines.delay(20)
                overlap.decrementAndGet()
            },
        )

        val a = launch { deployer.deploy(config(), 5555, VdDeploy.JAR_PATH) {} }
        val b = launch { deployer.deploy(config(), 5555, VdDeploy.JAR_PATH) {} }
        a.join(); b.join()

        assertEquals("不得有两轮部署同时在跑", 1, maxOverlap.get())
    }

    /**
     * D-M6：部署序列必须可取消。
     *
     * 此前内部包 `runBlocking { vdRunDeploySequence(sleep = Thread.sleep) }`——部署
     * 一次性跑完，调用方取消它要等整段结束（最坏 6s+，且期间共享 runner 上的命令
     * 继续跑）。现在 sleep seam 是挂起的，取消应该在 await 点上生效。
     */
    @Test
    fun `部署序列可被取消`() = runBlocking {
        val runner = FakeRunner(probeAlive = { true })
        // 真 sleep（不推进假时钟）→ 序列一直卡在"等旧引擎退出"
        val deployer = AdbDeployer(runner = runner, sleep = { kotlinx.coroutines.delay(50) })

        val job = launch { deployer.deploy(config(), 5555, VdDeploy.JAR_PATH) {} }
        kotlinx.coroutines.delay(300) // 让序列跑起来（正卡在等旧引擎退出）
        job.cancel()
        kotlinx.coroutines.withTimeout(2_000) { job.join() }

        // 取消后不应走到最后一步（启动引擎）
        assertTrue(
            "取消后不应继续启动引擎: ${runner.shellCommands()}",
            runner.shellCommands().none { it.contains("app_process") },
        )
    }
}
