package com.dilinkauto.desktop.video

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.VideoMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [VideoDecodePipeline] 的**纯逻辑 / 时序**单测（audit D-M2 / D-M3）。
 *
 * 端到端的"字节 → 图像"在 [VideoDecodePipelineSmokeTest] 里用真实 FFmpeg 覆盖；
 * 这里只测两条修复的判据本身，都不需要解码器真的出图：
 *  - D-M3 的重建退避曲线（退避形状直接决定"持续失败时是否空转"）；
 *  - D-M2 的硬解回退检查：不再挂在喂帧路径上（一帧不喂也必须能回退）。
 */
class VideoDecodePipelineTest {

    private fun frame(payload: ByteArray) =
        FrameCodec.Frame(Channel.VIDEO, VideoMsg.FRAME, payload)

    // ─── D-M3：指数退避 ───

    @Test
    fun `重建退避前几次保持紧凑恢复`() {
        // 单次码流抖动值得快速爬起来：前 3 次仍是 500ms
        assertEquals(500L, backoff(1))
        assertEquals(500L, backoff(2))
        assertEquals(500L, backoff(3))
    }

    @Test
    fun `重建退避之后指数翻倍`() {
        assertEquals(1_000L, backoff(4))
        assertEquals(2_000L, backoff(5))
        assertEquals(4_000L, backoff(6))
        assertEquals(8_000L, backoff(7))
    }

    @Test
    fun `重建退避封顶 8 秒`() {
        // 再多次也不再增长（否则坏码流下等待会失控）
        assertEquals(8_000L, backoff(30))
        assertEquals(8_000L, backoff(1_000))
        assertEquals(8_000L, backoff(Long.MAX_VALUE))
    }

    /** [VideoDecodePipeline.rebuildBackoffMs] 是纯函数，构造实例即可拿到（不起线程）。 */
    private fun backoff(rebuilds: Long): Long = VideoDecodePipeline().rebuildBackoffMs(rebuilds)

    // ─── D-M2：硬解回退检查挪出 feed 路径 ───

    /**
     * 判据只看时钟：时钟走过容忍窗口 → 回退软解，与有没有帧喂入无关。
     *
     * 旧实现的检查在 [VideoDecodePipeline.feed] 的最后一步，而 feed 开头有
     * `gate ?: return`：解码器卡在 `grabber.start()`（管道还没建立）时检查跑不到，
     * 硬解挂了就永远挂着，只剩黑屏。这里直接驱动检查来锁住判据本身。
     */
    @Test
    fun `时钟走过容忍窗口即回退软解_不需要任何帧`() {
        var now = 0L
        val watchdog = HardwareDecodeWatchdog(timeoutMs = 3_000, clock = { now })
        val pipeline = VideoDecodePipeline(
            hwaccel = VideoDecodePipeline.HWACCEL_D3D11VA,
            hardwareWatchdog = watchdog,
        )
        assertTrue("构造时应在用硬解", pipeline.hardwareInUse)
        watchdog.onStreamStarted()

        // 时钟没走满 → 不判超时（避免"一上来就回退"的假阳性）
        now = 2_999
        pipeline.checkHardwareFallback()
        assertTrue("容忍窗口内不应回退", pipeline.hardwareInUse)

        now = 3_001
        pipeline.checkHardwareFallback()
        assertFalse("时钟走过容忍窗口必须回退（与有没有帧无关）", pipeline.hardwareInUse)
    }

    /**
     * 回退是**一次性的**：已经回退过之后不再反复触发。
     *
     * （[HardwareDecodeWatchdog] 的 `resolved` 闩锁保证；这条锁住的是"回退后不会
     * 每秒翻一次状态"的语义。）
     */
    @Test
    fun `回退之后不再重复触发`() {
        var now = 0L
        val watchdog = HardwareDecodeWatchdog(timeoutMs = 1_000, clock = { now })
        val pipeline = VideoDecodePipeline(
            hwaccel = VideoDecodePipeline.HWACCEL_D3D11VA,
            hardwareWatchdog = watchdog,
        )
        watchdog.onStreamStarted()
        now = 5_000

        pipeline.checkHardwareFallback()
        assertFalse(pipeline.hardwareInUse)
        // 再来一次（喂帧路径早已不跑它，但定时线程会继续问）
        pipeline.checkHardwareFallback()
        assertFalse("回退状态应当稳定", pipeline.hardwareInUse)
    }

    /**
     * 喂帧路径上**不再**做回退检查（D-M2 的另一半）。
     *
     * `timeoutMs = 0` 的看门狗意味着"只要被问一次就判超时"—— 若检查还挂在 feed
     * 上，这一帧喂下去立刻回退。未 [VideoDecodePipeline.start]，所以唯一可能触发
     * 检查的就是 feed 本身。
     */
    @Test
    fun `feed 不再内联触发硬解回退检查`() {
        val watchdog = HardwareDecodeWatchdog(timeoutMs = 0, clock = { 0L })
        val pipeline = VideoDecodePipeline(
            hwaccel = VideoDecodePipeline.HWACCEL_D3D11VA,
            hardwareWatchdog = watchdog,
        )

        pipeline.feed(frame(byteArrayOf(0, 0, 0, 1, 0x65)))

        assertTrue("feed 路径上不应再做回退检查", pipeline.hardwareInUse)
    }

    /**
     * 真正的 D-M2 场景：**一帧都不喂**，硬解看门狗也要能把它回退掉。
     *
     * 用**日志文本**做判据而不是只看 `hardwareInUse` —— 两条回退路径的日志不同：
     * 定时线程（[VideoDecodePipeline.checkHardwareFallback]）打"内无任何输出"，
     * 解码线程自己的 `grabber.start()` 失败打"启动失败"。冻结时钟 + `timeoutMs=0`
     * 让定时线程在第一次轮询（≤100ms）就判超时，远早于 FFmpeg 自己的失败路径。
     *
     * 断言 `framesFed == 0` 把"没喂帧"这个前提钉死：检查能触发就不是 feed 的功劳。
     */
    @Test(timeout = 60_000)
    fun `一帧不喂_硬解看门狗也由定时线程驱动并回退软解`() {
        val watchdog = HardwareDecodeWatchdog(timeoutMs = 0, clock = { 0L })
        val pipeline = VideoDecodePipeline(
            hwaccel = VideoDecodePipeline.HWACCEL_D3D11VA,
            hardwareWatchdog = watchdog,
        )
        val logs = CopyOnWriteArrayList<String>()
        pipeline.onLog = { logs.add(it) }

        pipeline.start()
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (logs.none { it.contains("内无任何输出") } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            pipeline.stop()
        }

        assertFalse("一帧不喂也必须能回退（检查不能挂在 feed 路径上）", pipeline.hardwareInUse)
        assertTrue(
            "回退必须来自定时线程（而不是解码线程的启动失败路径）: $logs",
            logs.any { it.contains("内无任何输出") },
        )
        assertEquals("这个用例的前提：全程没有喂过帧", 0L, pipeline.framesFed.get())
    }

    /** feed 在没有任何管道时（解码线程还没建起来）必须是安全的 no-op。 */
    @Test
    fun `没有管道时 feed 是安全的 no-op`() {
        val pipeline = VideoDecodePipeline()
        // 未 start：pipe/gate 都是 null。生产时序里前几帧就是这样被丢掉的。
        pipeline.feed(frame(byteArrayOf(0, 0, 0, 1, 0x65)))
        assertEquals(1L, pipeline.framesFed.get())
        assertEquals(0L, pipeline.framesDecoded.get())
    }
}
