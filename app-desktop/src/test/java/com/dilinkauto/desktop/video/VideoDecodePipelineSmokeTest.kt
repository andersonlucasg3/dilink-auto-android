package com.dilinkauto.desktop.video

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.VideoMsg
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [VideoDecodePipeline] 的端到端冒烟测试（audit WIN-15 ④）。
 *
 * 用真实 FFmpeg 走一遍完整链路：CONFIG（SPS/PPS）→ IDR → P 帧 → `onImage` 出图。
 * 覆盖纯逻辑测试（[FeedGateTest]）碰不到的部分：FFmpegFrameGrabber 的原始码流
 * 初始化、NAL 解析、`Java2DFrameConverter` → BufferedImage 的转出。
 *
 * 两个实测出来的测试设计约束（别按"喂完就等"的直觉改）：
 *  - **必须持续投喂**：raw h264 的流信息探测（`grabber.start()`）在"喂完即止"
 *    的小数据量下不会完成 —— 探测要求的"参数就绪"需要解码器真的解出画面，而
 *    packet 攒批也需要足够字节。所以这里循环重放码流直到出帧，这也正是生产
 *    时序：手机是**不停**推流的，没有"喂完"这一时刻。
 *  - 所以码流不落测试资源文件，用 [FFmpegFrameRecorder] 现场编码（本机 FFmpeg
 *    没有可用 H.264 编码器时整个用例跳过）。
 */
class VideoDecodePipelineSmokeTest {

    private val width = 128
    private val height = 96

    @Test
    fun `真实 H 264 码流能被端到端解码出画面`() = runBlocking {
        val stream = encodeLocalH264()
        assumeTrue("本机 FFmpeg 无可用 H.264 编码器，跳过冒烟", stream != null)
        stream!!

        val nals = splitNals(stream)
        val config = concat(nals.filter { nalType(it) == NAL_SPS || nalType(it) == NAL_PPS })
        val firstIdr = nals.indexOfFirst { nalType(it) == NAL_IDR }
        assertTrue("编码输出里应当有 SPS/PPS", config.isNotEmpty())
        assertTrue("编码输出里应当有 IDR（实际 NAL 类型 ${nals.map(::nalType)}）", firstIdr >= 0)
        // 保留第一个 IDR 之后的**全部**画面 NAL（含后续 GOP 的 IDR）—— 与编码器输出顺序一致
        val videoFrames = nals.drop(firstIdr)
            .filter { nalType(it) == NAL_IDR || nalType(it) == NAL_NON_IDR }
            .map { FrameCodec.Frame(Channel.VIDEO, VideoMsg.FRAME, it) }

        val pipeline = VideoDecodePipeline()
        val decoded = CopyOnWriteArrayList<Pair<Int, Int>>()
        pipeline.onImage = { image -> decoded += image.width to image.height }
        pipeline.onLog = { }
        pipeline.start()
        try {
            // 解码线程要先把 pipe/gate 建起来；在那之前 feed 会被静默丢弃（设计如此）。
            // 重复喂 CONFIG 直到被接收 —— 被丢弃的那些不会进管道，不会污染码流。
            val configFrame = FrameCodec.Frame(Channel.VIDEO, VideoMsg.CONFIG, config)
            withTimeout(15_000) {
                while (pipeline.configsFed.get() == 0L) {
                    pipeline.feed(configFrame)
                    delay(10)
                }
            }

            // 持续投喂（模拟生产时序）：重放 [CONFIG + 从 IDR 起的全部画面帧]
            try {
                withTimeout(20_000) {
                    while (decoded.size < 2) {
                        pipeline.feed(configFrame)
                        videoFrames.forEach { pipeline.feed(it) }
                        delay(30)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                fail(
                    "持续投喂后仍未解出画面：decoded=${decoded.size} fed=${pipeline.framesFed.get()} " +
                        "rebuilds=${pipeline.decoderRebuilds.get()}",
                )
            }
        } finally {
            pipeline.stop()
        }

        assertTrue("至少应解出两帧（实际 ${decoded.size}）", decoded.size >= 2)
        assertEquals("解出的画面尺寸应与编码输入一致", width to height, decoded[0])
    }

    // ─── 码流生成与切分 ───

    /** 现场编码一小段 H.264 裸流；没有可用编码器时返回 null（用例跳过）。 */
    private fun encodeLocalH264(): ByteArray? {
        val out = ByteArrayOutputStream()
        val recorder = FFmpegFrameRecorder(out, width, height)
        val converter = Java2DFrameConverter()
        return try {
            recorder.setFormat("h264")
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264)
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P)
            recorder.setFrameRate(10.0)
            recorder.setGopSize(10)
            recorder.start()
            repeat(40) { i ->
                val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                val g = image.createGraphics()
                g.color = if (i % 2 == 0) Color.RED else Color.BLUE
                g.fillRect(0, 0, width, height)
                g.dispose()
                recorder.record(converter.convert(image))
            }
            recorder.stop()
            recorder.release()
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            null
        }
    }

    /** 把 Annex B 码流切成 NAL 单元（每段保留自己的起始码）。 */
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { idx, start ->
            data.copyOfRange(start, starts.getOrElse(idx + 1) { data.size })
        }
    }

    /** NAL 单元类型（起始码后第一个字节的低 5 位）。 */
    private fun nalType(nal: ByteArray): Int {
        val header = if (nal[2] == 1.toByte()) 3 else 4
        return nal[header].toInt() and 0x1F
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private companion object {
        const val NAL_NON_IDR = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}
