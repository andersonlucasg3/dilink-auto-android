package com.dilinkauto.server.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级钉子（issue #200）：停顿分级必须接在喂帧线程已有的 tick 上，且**不得绕开**
 * 既有的 IDR 限流链路。
 *
 * 现场缺陷：`STALL` 只在恢复那一帧才打（`RenderStallDetector.onTick` 无渲染推进即
 * return null），于是 6s 空档期间车机端没有任何动作，恢复时还要先吃一轮积压洪峰。
 *
 * 三条约束不能用单测直接驱动（[VideoDecoder] 依赖 MediaCodec），所以按仓库既有做法
 * 做源码文本断言：
 *  1. GAP 分支必须先 `noteGap()` —— 由它经 `onNeedsSyncFrame` → `SyncRequestGate`
 *     限流（issue #75 / #163），视频层不得自己发 `REQUEST_SYNC_FRAME`；
 *  2. GAP / STALLED 行必须带控制通道静默时长（`CtrlSilent`），用于区分「链路断」
 *     与「编码停」；
 *  3. 协议层的静默时长只读访问器必须来自 `Connection`（单一来源，不另起口径）。
 */
class StallEscalationWiringTest {

    private val decoder = sourceOf("app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt")
    private val carService = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt")
    private val connection = sourceOf("protocol-core/src/main/java/com/dilinkauto/protocol/Connection.kt")

    @Test
    fun `喂帧线程的 tick 必须驱动分级策略`() {
        assertTrue(
            "VideoDecoder 的喂帧循环必须调用 StallEscalationPolicy.onTick（issue #200）",
            decoder.contains("stallEscalation.onTick("),
        )
        assertTrue(
            "分级策略必须随会话边界复位",
            decoder.contains("stallEscalation.reset()"),
        )
    }

    @Test
    fun `GAP 级先预置重同步再索要 IDR`() {
        val gapBranch = decoder
            .substringAfter("StallEscalationPolicy.Level.GAP")
            .substringBefore("StallEscalationPolicy.Level.STALLED")
        assertTrue(
            "GAP 分支必须调用 noteGap()（预置 awaitingIdr + 经 SyncRequestGate 索要一次 IDR）",
            gapBranch.contains("noteGap()"),
        )
        assertFalse(
            "视频层不得自己发 REQUEST_SYNC_FRAME —— 绕过 SyncRequestGate 会回到 issue #70 的 IDR 风暴",
            gapBranch.contains("REQUEST_SYNC_FRAME"),
        )
    }

    @Test
    fun `分级日志必须能区分链路断与编码停`() {
        assertTrue(
            "VideoDecoder 需要控制通道静默时长的注入点（issue #200 的 C）",
            decoder.contains("msSinceControlFrame"),
        )
        val gapAndStall = decoder
            .substringAfter("StallEscalationPolicy.Level.GAP")
            .substringBefore("var frame = frameQueue.poll()")
        assertTrue(
            "GAP / STALLED 行必须打印控制通道静默时长",
            gapAndStall.contains("ctrlSilent"),
        )
        assertTrue(
            "车机端必须把控制连接的静默时长接进解码器",
            carService.contains("videoDecoder.msSinceControlFrame"),
        )
    }

    @Test
    fun `静默时长口径只来自 Connection`() {
        assertTrue(
            "protocol-core 的 Connection 必须提供 msSinceLastFrameReceived()",
            connection.contains("fun msSinceLastFrameReceived("),
        )
        assertFalse(
            "不得在车机端另抄一份 lastFrameReceived 口径",
            carService.contains("lastFrameReceivedAt"),
        )
    }

    /**
     * issue #200 评审：`LINK GAP` / `STALLED` 是实车回签要 grep 的证据，release 包
     * `CarLogWriter.enabled` 默认为 false，普通 `logSink → carLogSend` 会被直接丢掉
     * （#191/#197 的教训）。
     */
    @Test
    fun `分级日志必须绕过 enabled 才能上车`() {
        val gapAndStall = decoder
            .substringAfter("StallEscalationPolicy.Level.GAP")
            .substringBefore("var frame = frameQueue.poll()")
        assertTrue(
            "LINK GAP / STALLED 必须走 logDiagW（diagSink），不能走受 enabled 限制的 logW",
            gapAndStall.contains("logDiagW(") && !gapAndStall.contains("logW(\"LINK GAP"),
        )
        assertTrue(
            "车机端必须把 diagSink 接到 CarLogWriter.sendUnconditional()",
            carService.contains("videoDecoder.diagSink") &&
                carService.contains("logWriter.sendUnconditional(msg)"),
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
