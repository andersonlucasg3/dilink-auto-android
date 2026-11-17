package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
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
 * 判据落在生产类 [CachedKeyFrameStore] 上（加锁的写入 / 取出 / 作废），这里直接驱动它，
 * 再用结构性守卫钉住 VideoDecoder 的调用点（代次来源、冷启动请求 IDR）。
 */
class CachedKeyFrameGenerationTest {

    /** 4 字节起始码 + NAL type 5（IDR）。 */
    private val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x0A, 0x10, 0x20)

    @Test
    fun `跨代缓存 IDR 不得复用`() {
        val store = CachedKeyFrameStore()
        store.replace(generation = 3, data = idrFrame)

        val taken = store.take(currentGeneration = 4)

        assertTrue(
            "上一代 codec 的 IDR 必须判为作废，实际：$taken",
            taken is CachedKeyFrameStore.TakeResult.Discarded,
        )
        assertEquals("作废条目必须被带走（用于记 drop）", 3, (taken as CachedKeyFrameStore.TakeResult.Discarded).entry.generation)
        assertTrue("取出后缓存必须清空", store.take(4) is CachedKeyFrameStore.TakeResult.Empty)
    }

    @Test
    fun `同一 codec 实例的缓存 IDR 仍可复用`() {
        val store = CachedKeyFrameStore()
        store.replace(generation = 4, data = idrFrame)

        val taken = store.take(currentGeneration = 4)

        assertTrue(
            "switchSurface 不换 codec 的语义必须保留：同代缓存仍可当参考帧，实际：$taken",
            taken is CachedKeyFrameStore.TakeResult.Reusable,
        )
        assertTrue(
            "复用路径必须拿到原始码流",
            (taken as CachedKeyFrameStore.TakeResult.Reusable).entry.data.contentEquals(idrFrame),
        )
    }

    @Test
    fun `停播期缓存没有活着的 codec 时不得复用`() {
        val store = CachedKeyFrameStore()
        store.replace(generation = 0, data = idrFrame)
        assertTrue(
            "停播期缓存（无 codec 代次）不能被当成哪个 codec 的参考帧",
            store.take(currentGeneration = 4) is CachedKeyFrameStore.TakeResult.Discarded,
        )

        store.replace(generation = -1, data = idrFrame)
        assertTrue(
            "已释放代次的缓存同样不可复用",
            store.take(currentGeneration = 4) is CachedKeyFrameStore.TakeResult.Discarded,
        )
    }

    @Test
    fun `旧代 IDR 与新代 P 帧不会进入渲染路径`() {
        val store = CachedKeyFrameStore()
        val resync = ResyncPolicy()

        // 新 codec 起来：唯一缓存来自 generation=3，当前 codec 是 generation=4。
        store.replace(generation = 3, data = idrFrame)
        val taken = store.take(currentGeneration = 4)
        assertTrue("旧代 IDR 必须判为作废", taken is CachedKeyFrameStore.TakeResult.Discarded)

        // VideoDecoder 丢弃旧帧后进入 awaitingIdr（冷启动分支同一语义）。
        resync.onGap()
        assertTrue("旧帧作废后必须进入 awaitingIdr", resync.awaitingIdr)

        // 新流第一批 P 帧：没有合法参考帧，必须被拒喂 —— 不喂就不会有输出上屏。
        assertFalse("新代 P 帧必须被拒喂，到不了解码/渲染", resync.shouldFeed(isKeyFrame = false))
        assertFalse("后续 P 帧同样被拒喂", resync.shouldFeed(isKeyFrame = false))

        // 新 IDR 到达后才恢复。
        assertTrue("新 IDR 到达后才恢复喂入", resync.shouldFeed(isKeyFrame = true))
        assertFalse("恢复后不再等 IDR", resync.awaitingIdr)
    }

    @Test
    fun `取出后并发作废不会重复销账`() {
        val store = CachedKeyFrameStore()
        store.replace(generation = 3, data = idrFrame)
        assertTrue(store.take(4) is CachedKeyFrameStore.TakeResult.Discarded)

        assertFalse(
            "条目已被取走，CONFIG / stop 的 clear 不得再返回 true（否则同一帧记两次 drop，pending 变负）",
            store.clear(),
        )
    }

    @Test
    fun `缓存写入必须记录当前 codec 代次`() {
        val src = sourceOf(DECODER_PATH)
        assertTrue(
            "停播期缓存必须带 codecGeneration，写死 0 会让跨代判定彻底失效（issue #216）",
            src.contains("cachedKeyFrameStore.replace(codecGeneration, data)"),
        )
    }

    @Test
    fun `冷启动补喂缓存 IDR 之前必须校验 codec 代次`() {
        val src = sourceOf(DECODER_PATH)
        val guard = src.indexOf("cachedKeyFrameStore.take(generation)")
        val feedLog = src.indexOf("Feeding cached KEYFRAME for cold-start reference")

        assertTrue(
            "冷启动补喂缓存 IDR 之前没有代次校验：旧代 IDR 会被当成新 codec 的参考（issue #216）",
            guard >= 0,
        )
        assertTrue(
            "代次判定必须发生在补喂缓存 IDR 之前，实际 guard=$guard feedLog=$feedLog",
            guard in 0 until feedLog,
        )
        assertTrue(
            "跨代结果必须被显式处理（Discarded → 记 drop + 等新 IDR）",
            src.contains("CachedKeyFrameStore.TakeResult.Discarded"),
        )
    }

    @Test
    fun `冷启动没有可用参考帧时必须走 noteGap 主动请求 IDR`() {
        val src = sourceOf(DECODER_PATH)
        val coldStart = src.indexOf("if (!reusedCachedKeyFrame) {")
        assertTrue("找不到冷启动分支", coldStart >= 0)

        val guarded = src.indexOf("if (requestIdrOnColdStart) {", coldStart)
        val noteGap = src.indexOf("noteGap(coldStart = true)", guarded)
        assertTrue(
            "冷启动默认路径必须调用 noteGap(coldStart = true) 主动请求一次 IDR，实际 coldStart=$coldStart guarded=$guarded noteGap=$noteGap",
            guarded in (coldStart + 1)..(coldStart + 400) && noteGap in (guarded + 1)..(guarded + 300),
        )
        assertTrue(
            "冷启动请求原因必须是统一字符串，便于与真实断链区分",
            src.contains("requesting IDR (reason=\$COLD_START_IDR_REASON)"),
        )
        assertTrue(
            "冷启动 reason 常量值必须与 PR 说明一致",
            src.contains("""internal const val COLD_START_IDR_REASON = "cold start without a usable reference frame""""),
        )
    }

    @Test
    fun `冷启动与真实断链使用不同的等待日志与请求原因`() {
        val coldLogs = mutableListOf<String>()
        var coldReason: String? = null
        val cold = VideoDecoder().apply {
            logSink = { coldLogs += it }
            onNeedsSyncFrame = { coldReason = it }
        }
        assertTrue(cold.noteGap(coldStart = true))
        assertTrue(
            "冷启动必须单独一句措辞，实际：$coldLogs",
            coldLogs.any { it.contains("冷启动") && it.contains("首个 IDR") },
        )
        assertFalse(
            "冷启动不得打真实断链日志，否则回签会误判：$coldLogs",
            coldLogs.any { it.contains("Reference chain broken") },
        )
        assertEquals(
            "冷启动请求原因必须统一",
            "cold start without a usable reference frame",
            coldReason,
        )

        val gapLogs = mutableListOf<String>()
        var gapReason: String? = null
        val gap = VideoDecoder().apply {
            logSink = { gapLogs += it }
            onNeedsSyncFrame = { gapReason = it }
        }
        assertTrue(gap.noteGap())
        assertTrue(
            "真实断链必须保留原日志，实际：$gapLogs",
            gapLogs.any { it.contains("Reference chain broken") },
        )
        assertEquals("real gap 的请求原因必须保持原样", "reference chain broken", gapReason)
    }

    @Test
    fun `重建路径只保留一个 IDR 请求点`() {
        val src = sourceOf(DECODER_PATH)
        val recreateStart = src.indexOf("fun recreateDecoder(")
        val recreateEnd = src.indexOf("fun requestSyncAfterRebuild(")
        assertTrue("找不到 recreateDecoder 函数体", recreateStart in 0 until recreateEnd)
        val recreateBody = src.substring(recreateStart, recreateEnd)

        assertTrue(
            "重建必须关掉 start() 的冷启动请求，否则 SyncRequestGate 窗口内会真的发两次 IDR",
            recreateBody.contains("requestIdrOnColdStart = false"),
        )
        assertEquals(
            "重建入口必须继续走 requestSyncAfterRebuild 这一个请求点",
            1,
            Regex("""requestSyncAfterRebuild\(""").findAll(recreateBody).count(),
        )
    }

    @Test
    fun `noteGap 只在首次缺口请求一次 IDR`() {
        var requests = 0
        val d = VideoDecoder().apply {
            logSink = {}
            onNeedsSyncFrame = { _ -> requests++ }
        }

        assertTrue("首次缺口必须真的发出请求", d.noteGap())
        assertEquals("一次缺口只能占一个闸门名额", 1, requests)

        assertFalse("已处于 awaitingIdr 时不得重复请求", d.noteGap())
        assertEquals(1, requests)
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
