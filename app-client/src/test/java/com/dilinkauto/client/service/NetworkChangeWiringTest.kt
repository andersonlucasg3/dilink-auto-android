package com.dilinkauto.client.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级钉子（issue #200）：网络事件必须先过 [NetworkRebuildPolicy] 再决定是否拆会话。
 *
 * 现场缺陷是 `ConnectionService.onNetworkAvailable` 在 `CONNECTED/STREAMING` 下**没有分支**
 * （只有 `WAITING` 会重启监听循环），所以 Wi-Fi 重关联后视频链路静默 5.98s 期间手机端
 * 毫无动作；但反过来「一收到 available 就拆」更糟（全量重建实测 3.7~4.9s，且断链期间
 * 完不成握手）。这条断言把「先判静默再决定」的接线固定住。
 */
class NetworkChangeWiringTest {

    private val source = sourceOf("app-client/src/main/java/com/dilinkauto/client/service/ConnectionService.kt")

    @Test
    fun `网络可用时必须经过重建判据`() {
        val handler = source
            .substringAfter("override fun onNetworkAvailable(network: Network)")
            .substringBefore("override fun onNetworkLostConfirmed")
        assertTrue(
            "onNetworkAvailable 必须调用 NetworkRebuildPolicy（issue #200）",
            handler.contains("networkRebuildPolicy.onNetworkAvailable("),
        )
        assertTrue(
            "只有判据返回 REBUILD 才允许走主动拆会话",
            handler.contains("NetworkRebuildPolicy.Action.REBUILD") &&
                handler.contains("resetConnectionForNetworkChange()"),
        )
        assertTrue(
            "投屏中收到网络事件必须留下一行静默时长，便于下次实车对账",
            handler.contains("control silent"),
        )
        assertTrue(
            "冷却窗口用单调时钟（SystemClock.elapsedRealtime），墙钟跳变会算错",
            handler.contains("SystemClock.elapsedRealtime()"),
        )
    }

    @Test
    fun `停止服务时复位冷却`() {
        assertTrue(
            "stopEverything() 必须复位重建冷却，否则用户手动重开后 30s 内的网络事件被静默吞掉",
            source.substringAfter("private fun stopEverything()").contains("networkRebuildPolicy.reset()"),
        )
    }

    @Test
    fun `静默时长来自协议层的只读访问器`() {
        assertTrue(
            "必须用 Connection.msSinceLastFrameReceived() 取静默时长（单一口径）",
            source.contains("msSinceLastFrameReceived()"),
        )
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
