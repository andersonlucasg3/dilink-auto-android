package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.VideoMsg

/**
 * 有界发送队列 + 独立写线程：编码线程只做入队，绝不在 socket 写上空转。
 *
 * ## 为什么需要它（issue #3，2026-10-25 实车）
 *
 * 改造前 [GlPipeline.pipelineLoop] 在编码线程里同步写 socket（`GlPipeline.kt` 的
 * `writeFrame` → [FrameCodec.writeFrameToChannel]）。链路一慢，这一帧的写就把整个
 * 循环卡住：`updateTexImage()` 不再被调用 → SurfaceTexture/BufferQueue 反压 →
 * 虚拟显示整体停更。现场日志里 20fps 的管线出现过 120 帧耗时 24.18s（≈5fps）的
 * 窗口，最后 `FrameCodec.writeAll` 连续 5s 零进展抛 `IOException`，异常穿出管线，
 * 引擎退出、车机断连重连。
 *
 * 现在编码线程只 `offer()`：写不进队列就按直播优先策略丢帧，采集与编码节拍不再
 * 被链路吞吐绑定。5s 写超时仍然存在，但它只负责「链路确实死了」这一种情况。
 *
 * ## 策略
 *
 * - 队列按入队顺序由**唯一**写线程出队 → 写出（`AGENTS.md` §0：发送顺序由单线程
 *   派发器保证，入队顺序 = 调用顺序）；
 * - 队列满时：入队的是 CONFIG 或关键帧，就从队头驱逐**最老的 P 帧**再入队；
 *   入队的是 P 帧则直接丢弃新帧。丢过帧后参考链会断，由调用方按
 *   [Stats.dropped] 请求下一个 IDR（调用方负责限流）；
 * - 写抛异常（即 [FrameCodec.writeAll] 的 5s 无进展）→ [failed] 置位、队列清空、
 *   写线程退出；调用方看到 [failed] 后结束本轮管线，交给会话层重连。
 *
 * 纯 JVM 实现，不引用任何 Android 类型，便于单测。委托给 [AsyncSocketSender] 实现通用收敛。
 */
internal class VideoFrameSender(
    /** 队列容量（帧）。默认 8 帧 ≈ 2Mbps/20fps 下 400ms 的抖动余量。 */
    private val capacity: Int = DEFAULT_CAPACITY,
    /** 真正的写出动作（测试可注入假实现）。 */
    private val writeFrame: (FrameCodec.Frame) -> Unit,
    /** 关闭 video channel：用于解除 [FrameCodec.writeAll] 在写阻塞里的 parkNanos 自旋。 */
    private val closeChannel: () -> Unit = {}
) : AutoCloseable {

    /** 一次采样窗口内的观测值。[maxWriteNanos] 与 [dropped] 在采样后归零。 */
    internal data class Stats(val maxWriteNanos: Long, val dropped: Long)

    private class Pending(val msgType: Byte, val payload: ByteArray, val key: Boolean)

    @Volatile private var maxWriteNanos = 0L
    @Volatile private var dropped = 0L

    /** 最近一帧写耗时；不随 [sampleStats] 归零，供观察与测试读取。 */
    @Volatile var lastWriteNanos = 0L
        private set

    private val sender: AsyncSocketSender<Pending> = AsyncSocketSender<Pending>(
        capacity = capacity,
        threadName = "VideoSend",
        writeItem = { item ->
            writeFrame(FrameCodec.Frame(Channel.VIDEO, item.msgType, item.payload))
        },
        closeChannel = closeChannel,
        onWriteFailed = { e ->
            PipeLog.err("VideoSend: write failed, session should reconnect: ${e.message}")
        },
        onItemSent = { s, _, took ->
            lastWriteNanos = took
            s.withLock {
                if (took > maxWriteNanos) maxWriteNanos = took
            }
        }
    )

    /** 写线程因异常退出后置位；调用方据此结束管线。 */
    val failed: Boolean
        get() = sender.failed

    /** 启动写线程。重复调用返回已启动的实例（幂等）。 */
    fun start() {
        sender.start()
    }

    /**
     * 入队一帧。返回 true 表示已入队；false 表示被丢弃或队列已关闭。
     *
     * 不做任何 socket I/O，因此对编码线程是常数级开销（仅一次加锁 + 至多一次驱逐）。
     */
    fun offer(msgType: Byte, payload: ByteArray, key: Boolean): Boolean {
        // CONFIG 与关键帧同等保护：丢了它车机解码器会一直花屏到下一个自然 IDR。
        val protectedFrame = key || msgType == VideoMsg.CONFIG
        return sender.enqueue(Pending(msgType, payload, protectedFrame)) { q ->
            if (protectedFrame) {
                // 直播优先：驱逐最老的 P 帧，把位置让给关键帧。
                val victim = q.firstOrNull { !it.key }
                if (victim != null) q.remove(victim) else q.removeFirst()
                dropped++
                true
            } else {
                // 队列满且新帧是 P 帧：丢新帧，队列里更老的帧已经在路上。
                dropped++
                false
            }
        }
    }

    /**
     * 取走本窗口的统计并归零。
     *
     * 编码线程每轮调用一次：`maxWriteNanos` 喂给 [AdaptiveBitrate]，
     * `dropped` 用来触发一次（限流的）IDR 请求。
     */
    fun sampleStats(): Stats = sender.withLock {
        val s = Stats(maxWriteNanos, dropped)
        maxWriteNanos = 0L
        dropped = 0L
        s
    }

    /** 当前队列深度，仅用于日志。 */
    val queued: Int get() = sender.queued

    override fun close() {
        sender.close()
    }

    companion object {
        /** 默认容量：8 帧。太小吸收不了抖动，太大只会增加延迟。 */
        const val DEFAULT_CAPACITY = 8
    }
}
