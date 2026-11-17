package com.dilinkauto.client.display

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException

/**
 * issue #24 的回归钉子：命令 relay 必须把「正常断开」和「真实故障」分开记。
 *
 * 现场日志里 5 条 `[E][VirtualDisplayClient] Command relay error`（`null` /
 * `StandaloneCoroutine was cancelled` / `Channel closed: expected 1 bytes`）全部是
 * 噪音 —— 每次会话正常结束都会产生一条，把真实故障淹掉。
 */
class CommandRelayLoopTest {

    private class RecordingLog : RelayLog {
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable>>()

        override fun info(msg: String) { infos += msg }
        override fun warn(msg: String) { warns += msg }
        override fun error(msg: String, err: Throwable) { errors += msg to err }
    }

    @Test
    fun `对端在消息边界关闭时只记 I 且不产生 E`() = runTest {
        val log = RecordingLog()
        val end = commandRelayLoop(
            readType = { null },
            shouldContinue = { true },
            onMessage = { fail("对端已关闭，不应该再分发消息") },
            log = log
        )

        assertEquals(RelayEnd.PEER_CLOSED, end)
        assertEquals("正常断开必须有一条 I 级关闭日志：${log.infos}", 1, log.infos.size)
        assertTrue("正常断开不能记 E：${log.errors}", log.errors.isEmpty())
    }

    @Test
    fun `readType 抛 EOFException 时只记 I 且不产生 E`() = runTest {
        val log = RecordingLog()
        val end = commandRelayLoop(
            readType = { throw EOFException() },
            shouldContinue = { true },
            onMessage = { fail("对端已关闭，不应该再分发消息") },
            log = log
        )

        assertEquals(RelayEnd.PEER_CLOSED, end)
        assertTrue("EOF 是正常断开，不能记 E：${log.errors}", log.errors.isEmpty())
        assertTrue("EOF 应留下 I 级关闭日志", log.infos.isNotEmpty())
    }

    @Test
    fun `readType 抛 CancellationException 时必须原样上抛且不记 E`() = runTest {
        val log = RecordingLog()
        var propagated: Throwable? = null

        try {
            commandRelayLoop(
                readType = { throw CancellationException("relay cancelled") },
                shouldContinue = { true },
                onMessage = {},
                log = log
            )
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull("取消必须继续向上传播，不能当成普通异常吞掉", propagated)
        assertTrue("取消不是故障，不能记 E：${log.errors}", log.errors.isEmpty())
    }

    @Test
    fun `协程被 cancelAndJoin 时不得把取消记成 E`() = runTest {
        val log = RecordingLog()
        val started = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            commandRelayLoop(
                readType = {
                    started.complete(Unit)
                    awaitCancellation()
                },
                shouldContinue = { true },
                onMessage = {},
                log = log
            )
        }

        started.await()
        job.cancelAndJoin()

        assertTrue("relay 协程应处于已取消状态", job.isCancelled)
        assertTrue("disconnect 触发的取消是正常收尾，不能记 E：${log.errors}", log.errors.isEmpty())
    }

    @Test
    fun `本端关闭通道抛 ClosedChannelException 时只记 I 且返回 STOPPED`() = runTest {
        val log = RecordingLog()
        val end = commandRelayLoop(
            readType = { throw ClosedChannelException() },
            shouldContinue = { true },
            onMessage = { fail("本端已关闭，不应该再分发消息") },
            log = log
        )

        assertEquals("本端关闭是正常收尾，应返回 STOPPED", RelayEnd.STOPPED, end)
        assertEquals("本端关闭必须有一条 I 级日志：${log.infos}", 1, log.infos.size)
        assertTrue("本端关闭不能记 E：${log.errors}", log.errors.isEmpty())
    }

    @Test
    fun `本端异步关闭通道抛 AsynchronousCloseException 时只记 I 且返回 STOPPED`() = runTest {
        val log = RecordingLog()
        val end = commandRelayLoop(
            readType = { throw AsynchronousCloseException() },
            shouldContinue = { true },
            onMessage = { fail("本端已异步关闭，不应该再分发消息") },
            log = log
        )

        assertEquals("本端异步关闭是正常收尾，应返回 STOPPED", RelayEnd.STOPPED, end)
        assertEquals("本端异步关闭必须有一条 I 级日志：${log.infos}", 1, log.infos.size)
        assertTrue("本端异步关闭不能记 E：${log.errors}", log.errors.isEmpty())
    }

    @Test
    fun `真实故障仍然记 E 并返回 FAILED`() = runTest {
        val log = RecordingLog()

        val end = commandRelayLoop(
            readType = { throw IOException("Peer stalled mid-frame: 2 buffered of 4") },
            shouldContinue = { true },
            onMessage = {},
            log = log
        )

        assertEquals(RelayEnd.FAILED, end)
        assertEquals("真实故障必须保留 E：${log.errors}", 1, log.errors.size)
    }
}
