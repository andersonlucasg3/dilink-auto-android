package com.dilinkauto.server.decoder

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 重建解码器后的 IDR 补偿（issue #207 欠账，收口在 issue #205 的唯一重建入口里）。
 *
 * 要求「一次重建只发出一次 IDR 请求」，且请求必须经过 `onNeedsSyncFrame` →
 * `CarSessionCoordinator.requestSyncFrame` → `SyncRequestGate`（#75/#163 限流）。
 * 这里用假 gate（计数回调）驱动，并加一条结构性守卫防止调用方再补一次请求。
 */
class VideoDecoderRebuildSyncTest {

    private class FakeSurface : Surface(SurfaceTexture(0))

    private fun decoder(logs: MutableList<String>, gate: () -> Unit): VideoDecoder =
        VideoDecoder().apply {
            logSink = { logs += it }
            onNeedsSyncFrame = gate
        }

    @Test
    fun `重建成功后只发出一次 IDR 请求且留痕`() {
        var requests = 0
        val logs = mutableListOf<String>()
        val d = decoder(logs) { requests++ }

        // 生产路径里 recreateDecoder() 的**唯一**请求点。
        d.requestSyncAfterRebuild(rebuilt = true, reason = "surface rebind failed")

        assertEquals("一次重建只能占一个闸门名额", 1, requests)
        assertTrue(
            "必须留下可 grep 的请求痕迹，实际：$logs",
            logs.any { it.contains("requesting IDR (reason=surface rebind failed)") },
        )
    }

    @Test
    fun `重建失败不请求 IDR 以免白占闸门`() {
        var requests = 0
        val d = decoder(mutableListOf()) { requests++ }

        d.requestSyncAfterRebuild(rebuilt = false, reason = "surface rebind failed")

        assertEquals(0, requests)
    }

    @Test
    fun `recreateDecoder 在 start 失败时不请求 IDR`() {
        var requests = 0
        val d = decoder(mutableListOf()) { requests++ }

        // 单测环境没有 MediaCodec：start() 必然失败，running 保持 false。
        d.recreateDecoder(FakeSurface(), 1280, 720, 30, "surface rebind failed")

        assertFalse("重建失败后 running 必须为 false", d.isRunning)
        assertEquals("没有 codec 可解时不得发 IDR 请求", 0, requests)
    }

    @Test
    fun `一次重建只有一个请求点_调用方不得再请求`() {
        val decoderSrc = sourceOf(DECODER_PATH)
        // 只看 recreateDecoder 的函数体：`noteGap()` 里还有一处 onNeedsSyncFrame?.invoke()
        // （参考链断裂），那是另一条路径，不在「一次重建」的范围内。
        val bodyStart = decoderSrc.indexOf("fun recreateDecoder(")
        val bodyEnd = decoderSrc.indexOf("fun requestSyncAfterRebuild(")
        assertTrue("找不到 recreateDecoder 的函数体", bodyStart >= 0 && bodyEnd > bodyStart)
        val recreateBody = decoderSrc.substring(bodyStart, bodyEnd)
        assertEquals(
            "recreateDecoder 里必须有且只有一处 requestSyncAfterRebuild(...)",
            1,
            Regex("""requestSyncAfterRebuild\(""").findAll(recreateBody).count(),
        )
        assertEquals(
            "重建入口内不得直接触发 onNeedsSyncFrame（只能经 requestSyncAfterRebuild）",
            0,
            Regex("""onNeedsSyncFrame\?\.invoke\(\)""").findAll(recreateBody).count(),
        )

        val coordinatorSrc = sourceOf(COORDINATOR_PATH)
        assertTrue(
            "#198 回退必须走 recreateDecoder()",
            coordinatorSrc.contains("videoDecoder.recreateDecoder("),
        )
        assertFalse(
            "调用方不得在 recreateDecoder 之后再 requestSyncFrame，否则一次重建占两个名额",
            coordinatorSrc.contains("requestSyncFrame(\"decoder rebuilt"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录（同 RenderPipelinePolicyTest）。 */
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
        const val DECODER_PATH =
            "app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt"
        const val COORDINATOR_PATH =
            "app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt"
    }
}
