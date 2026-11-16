package com.dilinkauto.server.decoder

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #198：没有 Surface、或没有在当前 Surface 上渲染过帧时，不允许报 `STREAMING`。
 *
 * 现场（`client.log` 第 6 次会话）：`surfaceCreated` 从未回调、解码器 `running=false`，
 * 状态机却因为「收到一个视频帧」直接报 `Video ready — streaming`。
 *
 * 这里两部分断言：
 *  1. 判据本体（[RenderPipelinePolicy] / [SurfaceWaitLogger] / [VideoDecoder] 的可见输出计数）；
 *  2. 源码级接线钉子 —— 判据必须真的挡在 `CarSessionCoordinator` 的状态机前面，
 *     否则纯函数测试全绿也不代表故障被修好。
 */
class RenderPipelinePolicyTest {

    // ─── 1. STREAMING 判据 ───

    @Test
    fun `frame received without a running decoder must not enter streaming`() {
        // 现场第 6 次会话：手机在发流（收到 CONFIG + IDR），但 Surface 从未创建、解码器从未启动。
        assertFalse(
            "解码器没在跑就不允许报 STREAMING（issue #198 的缺陷形态）",
            RenderPipelinePolicy.canEnterStreaming(
                decoderRunning = false,
                outputSurfaceValid = false,
                framesRenderedOnSurface = 0L,
            ),
        )
    }

    @Test
    fun `running decoder with a fresh surface must not enter streaming until a frame is rendered`() {
        // HOME -> APP 切换：解码器一直在跑，但新 Surface 还一帧都没拿到。
        assertFalse(
            "新 Surface 上还没渲染过帧，不得报 STREAMING",
            RenderPipelinePolicy.canEnterStreaming(
                decoderRunning = true,
                outputSurfaceValid = true,
                framesRenderedOnSurface = 0L,
            ),
        )
        // surfaceDestroyed 之后、新 Surface 到达之前的窗口：闸门关闭。
        assertFalse(
            "Surface 已销毁（渲染闸门关闭）时不得报 STREAMING",
            RenderPipelinePolicy.canEnterStreaming(
                decoderRunning = true,
                outputSurfaceValid = false,
                framesRenderedOnSurface = 12L,
            ),
        )
    }

    @Test
    fun `decoder that rendered on the bound surface enters streaming`() {
        assertTrue(
            "解码器在跑 + Surface 有效 + 已渲染过帧，才允许进入 STREAMING",
            RenderPipelinePolicy.canEnterStreaming(
                decoderRunning = true,
                outputSurfaceValid = true,
                framesRenderedOnSurface = 1L,
            ),
        )
    }

    // ─── 2. surfaceCreated 分派 ───

    @Test
    fun `surface created dispatch follows decoder and surface state`() {
        assertEquals(
            "解码器已停 -> 冷启动",
            RenderPipelinePolicy.SurfaceAction.START_DECODER,
            RenderPipelinePolicy.onSurfaceCreated(decoderRunning = false, surfaceValid = true),
        )
        assertEquals(
            "解码器在跑 -> setOutputSurface 重绑",
            RenderPipelinePolicy.SurfaceAction.SWITCH_SURFACE,
            RenderPipelinePolicy.onSurfaceCreated(decoderRunning = true, surfaceValid = true),
        )
        assertEquals(
            "Surface 已失效 -> 什么都不做（等下一次回调）",
            RenderPipelinePolicy.SurfaceAction.IGNORE,
            RenderPipelinePolicy.onSurfaceCreated(decoderRunning = true, surfaceValid = false),
        )
        assertEquals(
            "Surface 已失效 + 解码器已停 -> 仍然什么都不做",
            RenderPipelinePolicy.SurfaceAction.IGNORE,
            RenderPipelinePolicy.onSurfaceCreated(decoderRunning = false, surfaceValid = false),
        )
    }

    // ─── 3. 等待日志节奏 ───

    @Test
    fun `wait logger emits at most one line per interval and restarts after reset`() {
        val logger = SurfaceWaitLogger(intervalMs = 2_000L)

        assertEquals("首次进入等待态必须立刻可见", 0L, logger.onNotReady(1_000L))
        assertNull("同一间隔内不得刷屏", logger.onNotReady(1_500L))
        assertEquals("满 2s 再报一次已等待秒数", 2L, logger.onNotReady(3_000L))
        assertNull("3s 处的第二次调用仍属同一间隔", logger.onNotReady(4_900L))
        assertEquals(4L, logger.onNotReady(5_000L))

        logger.reset()
        assertEquals("会话切换后重新计时", 0L, logger.onNotReady(9_000L))
    }

    // ─── 4. 解码器的「可见输出」计数 ───

    @Test
    fun `invalidateSurface resets the rendered-on-surface counter`() {
        val decoder = VideoDecoder()
        decoder.framesRenderedOnSurface = 30L

        decoder.invalidateSurface()

        assertEquals(
            "Surface 销毁后旧 Surface 的出帧数不再代表可见输出",
            0L,
            decoder.framesRenderedOnSurface,
        )
        assertFalse("invalidateSurface 同时必须关闭渲染闸门", decoder.outputSurfaceValid)
    }

    @Test
    fun `switchSurface reports failure instead of silently succeeding`() {
        val decoder = VideoDecoder()
        // codec 为 null（未启动）：旧实现返回 Unit，调用方无法区分成功/失败。
        assertFalse(
            "没有 codec 时重绑必须返回 false，调用方才能回退重建",
            decoder.switchSurface(FakeSurface(valid = true)),
        )
        assertFalse(
            "无效 Surface 必须返回 false",
            decoder.switchSurface(FakeSurface(valid = false)),
        )
    }

    // ─── 5. 接线钉子：判据必须真的挡在状态机前面 ───

    @Test
    fun `coordinator gates streaming on the render pipeline`() {
        val coordinator = sourceOf(COORDINATOR_PATH)

        assertTrue(
            "handleVideoFrame 必须用 RenderPipelinePolicy.canEnterStreaming 判定，而不是收到帧就置 _videoReady",
            coordinator.contains("RenderPipelinePolicy.canEnterStreaming("),
        )
        assertFalse(
            "不得回到「收到非 CONFIG 帧即 ready」的旧写法（issue #198 的缺陷形态）",
            coordinator.contains("First video frame — VD stream ready"),
        )
        assertTrue(
            "未就绪时必须按间隔输出 [Video] waiting for surface Ns 诊断行",
            coordinator.contains("[Video] waiting for surface"),
        )
    }

    @Test
    fun `coordinator routes surface callbacks through the policy`() {
        val coordinator = sourceOf(COORDINATOR_PATH)

        assertTrue(
            "surfaceCreated 必须经 RenderPipelinePolicy.onSurfaceCreated 分派",
            coordinator.contains("RenderPipelinePolicy.onSurfaceCreated("),
        )
        assertTrue(
            "重绑必须检查 switchSurface 的返回值，失败时回退重建而不是继续黑屏",
            coordinator.contains("if (videoDecoder.switchSurface(surface))"),
        )
    }

    @Test
    fun `decoder counts only frames actually released to a valid surface`() {
        val decoder = sourceOf(DECODER_PATH)

        assertTrue(
            "VideoDecoder 必须维护 framesRenderedOnSurface",
            decoder.contains("internal var framesRenderedOnSurface"),
        )
        assertTrue(
            "switchSurface 必须把真实结果返回给调用方（不能继续返回 Unit）",
            decoder.contains("fun switchSurface(newSurface: Surface): Boolean"),
        )
        assertTrue(
            "渲染闸门必须一次读出并用于判定是否计数",
            decoder.contains("val render = outputSurfaceValid"),
        )
    }

    private class FakeSurface(private var valid: Boolean) : Surface(SurfaceTexture(0)) {
        override fun isValid(): Boolean = valid
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录（同 SyncFrameRequestGateSourceTest）。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }

    private companion object {
        const val COORDINATOR_PATH =
            "app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt"
        const val DECODER_PATH =
            "app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt"
    }
}
