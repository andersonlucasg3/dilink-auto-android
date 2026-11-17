package com.dilinkauto.server.decoder

/**
 * 喂帧突发策略（issue #55）。
 *
 * 原实现每 `1000/fps` 毫秒只从 4 槽队列取 1 帧，消费上限被钉死在 30fps，
 * 队列没有任何突发余量：关键帧或积压帧一旦成串到达就只能丢帧，而丢帧会触发
 * 「等下一个 IDR」的重同步，画面冻结数秒。这里把「单轮最多连喂多少帧」抽成
 * 纯函数，既让解码器输入能力被用满，也便于单测锁定不再退化为单帧限速。
 *
 * 空闲时仍按帧间隔阻塞等待（见 `VideoDecoder`），避免忙等占 CPU。
 */
internal object FeedBurst {

    /**
     * 单轮最多连续喂的帧数。
     *
     * 队列容量 `VideoDecoder.FRAME_QUEUE_CAPACITY` = 8（issue #163 从 4 扩容），与这个上限
     * 对齐：消费侧单轮能吃 8 帧，缓冲深度也必须跟得上，否则突发时仍会丢帧 → 断链重同步。
     */
    const val MAX_FRAMES_PER_ROUND = 8

    /** 是否继续本轮排空：队列里还有帧，且本轮未达上限。 */
    fun shouldContinue(fedThisRound: Int, hasQueuedFrame: Boolean): Boolean =
        hasQueuedFrame && fedThisRound < MAX_FRAMES_PER_ROUND
}
