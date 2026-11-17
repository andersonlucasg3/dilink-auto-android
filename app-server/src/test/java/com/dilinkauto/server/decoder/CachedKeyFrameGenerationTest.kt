package com.dilinkauto.server.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冷启动补喂缓存 IDR 的代次守卫（issue #216）。
 *
 * 现场：新 codec 冷启动时无条件把停播期缓存的 184B 全黑 IDR 当参考帧喂进去，
 * `awaitingIdr` 被这道「参考帧已就位」骗过，随后到来的 P 帧全部解码成空帧（size=12），
 * 直到新 IDR 到达才恢复（约 9s 花屏）。
 *
 * 判据抽成纯函数 [shouldReuseCachedKeyFrame]，这里用「旧代 IDR + 新代 P 帧」的序列
 * 钉住行为，并加一条结构性守卫确认生产路径确实调用了它。
 */
class CachedKeyFrameGenerationTest {

    @Test
    fun `跨代缓存 IDR 不得复用`() {
        assertFalse(
            "上一代 codec 的 IDR 不能作为新 codec 的参考帧",
            shouldReuseCachedKeyFrame(cachedGeneration = 3, currentGeneration = 4),
        )
    }

    @Test
    fun `同一 codec 实例的缓存 IDR 仍可复用`() {
        assertTrue(
            "switchSurface 不换 codec 的语义必须保留：同代缓存仍可当参考帧",
            shouldReuseCachedKeyFrame(cachedGeneration = 4, currentGeneration = 4),
        )
    }

    @Test
    fun `停播期缓存没有活着的 codec 时不得复用`() {
        assertFalse(
            "停播期缓存（无 codec 代次）不能被当成哪个 codec 的参考帧",
            shouldReuseCachedKeyFrame(cachedGeneration = 0, currentGeneration = 4),
        )
        assertFalse(
            "已释放代次的缓存同样不可复用",
            shouldReuseCachedKeyFrame(cachedGeneration = -1, currentGeneration = 4),
        )
    }

    @Test
    fun `旧代 IDR 与新代 P 帧不会进入渲染路径`() {
        val resync = ResyncPolicy()

        // 新 codec 起来：唯一缓存来自 generation=3，当前 codec 是 generation=4。
        val reused = shouldReuseCachedKeyFrame(cachedGeneration = 3, currentGeneration = 4)
        assertFalse("旧代 IDR 不得作为新 codec 的参考帧", reused)

        // VideoDecoder 丢弃旧帧后进入 awaitingIdr（start() 里的冷启动分支同一语义）。
        if (!reused) resync.onGap()
        assertTrue("旧帧作废后必须进入 awaitingIdr", resync.awaitingIdr)

        // 新流第一批 P 帧：没有合法参考帧，必须被拒喂 —— 不喂就不会有输出上屏。
        assertFalse("新代 P 帧必须被拒喂，到不了解码/渲染", resync.shouldFeed(isKeyFrame = false))
        assertFalse("后续 P 帧同样被拒喂", resync.shouldFeed(isKeyFrame = false))

        // 新 IDR 到达后才恢复。
        assertTrue("新 IDR 到达后才恢复喂入", resync.shouldFeed(isKeyFrame = true))
        assertFalse("恢复后不再等 IDR", resync.awaitingIdr)
    }

    @Test
    fun `冷启动补喂缓存 IDR 之前必须校验 codec 代次`() {
        val src = sourceOf(DECODER_PATH)
        val guard = src.indexOf("shouldReuseCachedKeyFrame(")
        val feedLog = src.indexOf("Feeding cached KEYFRAME for cold-start reference")

        assertTrue(
            "冷启动补喂缓存 IDR 之前没有代次校验：旧代 IDR 会被当成新 codec 的参考（issue #216）",
            guard >= 0,
        )
        assertTrue(
            "代次判定必须发生在补喂缓存 IDR 之前，实际 guard=$guard feedLog=$feedLog",
            guard in 0 until feedLog,
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录（同 VideoDecoderRebuildSyncTest）。 */
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
    }
}
