package com.dilinkauto.server.decoder

/**
 * 收帧记账的唯一入口（issue #205）。
 *
 * 背景：`keys_recv / keys_fed / keys_drop` 原来是三个各自 `++` 的散字段 —— 接收入口、
 * 喂帧线程、队列驱逐各记各的，冷启动补喂缓存 IDR、`stop()` 清队列、`!configFed` 早退
 * 这些分支干脆不记。现场 `keys_recv=123 keys_fed=118 keys_drop=0`：差 5 无处可去，
 * 既不算喂入也不算丢弃，于是「丢了几个 IDR」永远算不出来。
 *
 * 这里把它收敛成一台账：每帧只能走 [onReceived] 入账一次，再由 [onFed] / [onDropped]
 * 之一销账一次（入口直接丢的用 [onRejected] 一步完成）。不变式：
 *
 * ```
 * received == fed + dropped + pending          // 全量帧
 * keyReceived == keyFed + keyDropped + keyPending  // 关键帧
 * ```
 *
 * `pending` 是「已接收、尚未喂入也未丢弃」的帧数（队列里 + 缓存待补喂的 IDR）。
 * 它由本类显式维护，不由队列长度推导 —— 两者刻意分开，才能真正发现「帧被清掉了
 * 但没记账」（[violationText] 会报），而不是让队列长度把漏记掩盖过去。
 *
 * 线程模型：网络线程入账、喂帧线程销账，所有读写都在 `synchronized(this)` 内。
 */
internal class IntakeAccounting {

    /** 某一时刻的四项快照（含关键帧分项），用于日志与不变式校验。 */
    data class Snapshot(
        val received: Long,
        val fed: Long,
        val dropped: Long,
        val pending: Long,
        val keyReceived: Long,
        val keyFed: Long,
        val keyDropped: Long,
        val keyPending: Long,
    ) {
        /** 全量帧是否平账。 */
        val streamBalanced: Boolean get() = received == fed + dropped + pending

        /** 关键帧是否平账。 */
        val keyBalanced: Boolean get() = keyReceived == keyFed + keyDropped + keyPending

        /** 全量帧与关键帧同时平账。 */
        val balanced: Boolean get() = streamBalanced && keyBalanced
    }

    private var received = 0L
    private var fed = 0L
    private var dropped = 0L
    private var pending = 0L

    private var keyReceived = 0L
    private var keyFed = 0L
    private var keyDropped = 0L
    private var keyPending = 0L

    /** 会话边界清零：`VideoDecoder.start()` 调用，保证一个会话一套账。 */
    @Synchronized
    fun reset() {
        received = 0L
        fed = 0L
        dropped = 0L
        pending = 0L
        keyReceived = 0L
        keyFed = 0L
        keyDropped = 0L
        keyPending = 0L
    }

    /** 接收一帧：进入「待喂/待丢」账。 */
    @Synchronized
    fun onReceived(isKey: Boolean) {
        received++
        pending++
        if (isKey) {
            keyReceived++
            keyPending++
        }
    }

    /** 成功喂给解码器一帧。 */
    @Synchronized
    fun onFed(isKey: Boolean) {
        fed++
        pending--
        if (isKey) {
            keyFed++
            keyPending--
        }
    }

    /** 丢弃一帧（它此前已经 [onReceived] 入账，例如队列驱逐、stop 清队列、喂入失败）。 */
    @Synchronized
    fun onDropped(isKey: Boolean) {
        dropped++
        pending--
        if (isKey) {
            keyDropped++
            keyPending--
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        received = received,
        fed = fed,
        dropped = dropped,
        pending = pending,
        keyReceived = keyReceived,
        keyFed = keyFed,
        keyDropped = keyDropped,
        keyPending = keyPending,
    )

    /**
     * 不变式违例描述（`null` = 平账）。
     *
     * 除了等式本身，还单独判 `pending < 0`：同一帧被既记喂入又记丢弃时，等式两边
     * 会被负数 pending「抹平」，只有这个下限检查能抓到重复销账。
     */
    @Synchronized
    fun violationText(): String? {
        val s = snapshot()
        if (s.pending >= 0L && s.keyPending >= 0L && s.balanced) return null
        return "recv=${s.received} fed=${s.fed} drop=${s.dropped} pending=${s.pending} " +
            "keys recv=${s.keyReceived} fed=${s.keyFed} drop=${s.keyDropped} pending=${s.keyPending}"
    }
}
