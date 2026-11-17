package com.dilinkauto.server.service

import com.dilinkauto.protocol.SyncRequestGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级钉子：车机端与手机端的 IDR 请求闸门必须共用 protocol-core 的同一个 [SyncRequestGate]
 * （issue #75 的「两端单一来源」结构，issue #163 的滑动窗口语义）。
 *
 * 缺陷形态是「两端各自写死一个阈值」——车机 500ms、手机 1000ms，于是距上次授予
 * 500–999ms 的补帧请求被车机放行、又被手机吞掉（issue #75）；或者两端共用「硬性 1s 间隔」，
 * 吞掉刚恢复又断链的二次请求，车机干等 3s 自然 GOP（issue #163）。本文件把
 * 「两端同判 + 二次缺口放行」做成可执行断言。
 *
 * 刻意只读源码文本 + 直接驱动 protocol-core 的纯状态机，不依赖 Android 运行时。
 */
class SyncFrameRequestGateSourceTest {

    // issue #198：IDR 请求（含「Surface 重绑失败后重建解码器」这条新触发源）统一收敛到
    // CarSessionCoordinator.requestSyncFrame，共享闸门也随之落在那里。
    private val carPath = "app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt"
    private val phonePath = "vd-server/src/main/java/com/dilinkauto/vdserver/GlPipeline.kt"
    private val corePath = "protocol-core/src/main/java/com/dilinkauto/protocol/VideoConfig.kt"
    private val gatePath = "protocol-core/src/main/java/com/dilinkauto/protocol/SyncRequestGate.kt"

    private val car = sourceOf(carPath)
    private val phone = sourceOf(phonePath)
    private val core = sourceOf(corePath)
    private val gate = sourceOf(gatePath)

    /** 不允许再出现的模块内私有阈值常量（车机 ms / 手机 nanos / 窗口 / 次数等旧书写方式）。 */
    private val moduleLocalGateConst =
        Regex("""const val\s+\w*(SYNC|IDR)\w*(INTERVAL|NANOS|WINDOW|LIMIT|MAX)\w*\s*=""")

    @Test
    fun `car applies the shared gate state machine`() {
        assertTrue(
            "车机端必须持并推进共享的 SyncRequestGate（单一来源），当前源码未匹配到",
            car.contains("syncRequestGate.get()") &&
                car.contains("tryAcquire(now)") &&
                car.contains("compareAndSet("),
        )
        assertFalse(
            "车机端不得再回到旧的硬性间隔判定（issue #163）",
            car.contains("syncFrameRequestAllowed"),
        )
        assertTrue(
            "IDR 请求必须只有一个入口 requestSyncFrame（参考链断裂 / 重建解码器共用闸门，issue #198）",
            car.contains("fun requestSyncFrame(reason: String)"),
        )
    }

    @Test
    fun `phone applies the shared gate state machine`() {
        assertTrue(
            "手机端必须用 SyncRequestGate.tryAcquire 推进共享状态（单一来源），当前源码未匹配到",
            phone.contains("syncRequestGate.tryAcquire(now)"),
        )
        assertFalse(
            "手机端不得再回到旧的硬性间隔判定（issue #163）",
            phone.contains("syncFrameRequestAllowed"),
        )
    }

    @Test
    fun `neither end declares a module local gate constant`() {
        for ((name, src) in listOf("CarConnectionService.kt" to car, "GlPipeline.kt" to phone)) {
            val hit = moduleLocalGateConst.find(src)
            assertFalse(
                "$name 又长出了模块内私有阈值常量 ${hit?.value}（issue #75 / #163 的缺陷形态：两端阈值漂移）",
                hit != null,
            )
        }
    }

    @Test
    fun `the sliding window lives only in protocol-core`() {
        assertTrue(
            "滑动窗口必须由 protocol-core 的 SyncRequestGate 实现",
            gate.contains("class SyncRequestGate") && gate.contains("fun tryAcquire(nowMs: Long)"),
        )
        for ((name, src) in listOf("CarConnectionService.kt" to car, "GlPipeline.kt" to phone)) {
            assertFalse(
                "$name 私自实现了闸门判定（fun tryAcquire），单一来源被破坏",
                src.contains("fun tryAcquire("),
            )
        }
    }

    /**
     * 死区重放：用 :protocol-core 源码里**实际生效的**窗口与次数驱动两端闸门。
     *
     * 现场：第 8 次重同步的请求刚被放行，298–305ms 后又断链；接着 999ms 内再来一次。
     * 旧语义（窗口内 1 次）会把二次请求吞掉；修复后两端必须同时放行，且第 3 次同时拒绝。
     */
    @Test
    fun `dead zone replay at the observed secondary gap is empty`() {
        val windowMs = literal(windowLiteral, "SYNC_FRAME_REQUEST_WINDOW_MS").toLong()
        val maxPerWindow = literal(maxLiteral, "SYNC_FRAME_REQUEST_MAX_PER_WINDOW").toInt()
        assertEquals("窗口长度必须仍是 1000ms（issue #75 的取值）", 1000L, windowMs)
        assertEquals("窗口内必须放行 2 次（issue #163）", 2, maxPerWindow)

        for (gapMs in listOf(298L, 305L, 747L, 999L)) {
            // 两端都从「刚放行一次」的同一状态出发，模拟二次缺口。
            val carFirst = SyncRequestGate(windowMs, maxPerWindow).tryAcquire(0L)
            val phoneFirst = SyncRequestGate(windowMs, maxPerWindow).tryAcquire(0L)
            val carSecond = carFirst!!.tryAcquire(gapMs)
            val phoneSecond = phoneFirst!!.tryAcquire(gapMs)
            assertFalse(
                "gap=${gapMs}ms 出现死区：车机放行=${carSecond != null} 手机授予=${phoneSecond != null}" +
                    " —— 请求注定被吞，车机要干等一个自然 GOP",
                carSecond != null && phoneSecond == null,
            )
            assertTrue(
                "gap=${gapMs}ms 的二次缺口必须被两端同时放行（issue #163 的修复目标）",
                carSecond != null && phoneSecond != null,
            )
            // 紧接着的同一时刻第 3 次必须两端同时拒绝，保住 issue #70 / #55 的 IDR 风暴闸门。
            val carThird = carSecond?.tryAcquire(gapMs)
            val phoneThird = phoneSecond?.tryAcquire(gapMs)
            assertEquals(
                "gap=${gapMs}ms 后窗口内第 3 次：两端决策必须一致且为拒绝",
                carThird != null,
                phoneThird != null,
            )
            assertTrue(
                "gap=${gapMs}ms 后窗口内第 3 次必须被拒（防 IDR 风暴）",
                carThird == null,
            )
        }
    }

    private val windowLiteral =
        Regex("""const val\s+SYNC_FRAME_REQUEST_WINDOW_MS\s*=\s*([\d_]+)L""")

    private val maxLiteral =
        Regex("""const val\s+SYNC_FRAME_REQUEST_MAX_PER_WINDOW\s*=\s*([\d_]+)""")

    /** 从 :protocol-core 的 VideoConfig.kt 里读实际生效的常量字面量（不另抄一个数）。 */
    private fun literal(regex: Regex, name: String): String {
        val m = regex.find(core) ?: error(":protocol-core 的 VideoConfig.kt 里找不到 $name 的字面量")
        return m.groupValues[1].replace("_", "")
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }
}
