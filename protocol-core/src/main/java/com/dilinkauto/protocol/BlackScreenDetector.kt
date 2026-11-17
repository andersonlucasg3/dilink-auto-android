package com.dilinkauto.protocol

/**
 * 持续黑屏检测 —— 车机端 `VideoDecoder` 与 Windows 接收端 `VideoDecodePipeline` 共用。
 *
 * 为什么落在 protocol-core：两端面对的是同一条编码链路、同一类故障 —— 手机侧的
 * VirtualDisplay 没产出（或应用没渲染）时 TCP 仍然通，接收端会**永远停在最后一帧**。
 * 判定策略是同一套，所以单点定义在这里（与 [H264NalParser] 同一模式）。
 *
 * 怎么判定：编码器对接近纯色的画面会输出极小的 I 帧，因此**连续多个极小关键帧**
 * 意味着码流是黑的。
 *
 * 两级反应，刻意不对称：
 *  - **告警**：连续 [alertStreak] 个极小关键帧后置位（[isAlerted]），只用于日志；
 *  - **升级**：只有黑屏持续满 [sustainMs] 才调用 [onSustainedBlackScreen]。
 *    应用 / VD 预热期间的短暂黑屏是正常的，在那里升级会引发重连风暴 —— 正是当初
 *    泄漏 VirtualDisplay 的那个故障模式。
 *
 * 升级每次会话最多触发一次，直到 [reset]。
 *
 * 纯码流判据分不清「内容本身是黑」和「管线卡死」（两者都是连续极小 I 帧），
 * 所以升级前可以再过一道 [decodingAliveCheck] 闸门：黑但解码仍在出帧 = 内容黑，
 * 不升级。桌面端已接线（2026-10-17），车机端已接线（2026-10-26，issue #11）。
 *
 * 时钟由调用方注入（[clock]）：车机端传 `SystemClock.elapsedRealtime()`，桌面端用
 * 默认的 `System.nanoTime()`。只要求"同一个单调源"，与绝对值无关；默认值不能再写
 * `SystemClock` —— 本模块是纯 JVM，不依赖 Android。
 */
class BlackScreenDetector(
    /** I 帧小于这个字节数即视为"极小"。 */
    private val keyframeMaxBytes: Int = 2 * 1024,
    /** 连续多少个极小关键帧后置告警位。 */
    private val alertStreak: Int = 3,
    /** 单调毫秒时钟；测试注入假时钟。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * 码流**持续**黑屏时调用一次 —— 不是第三个极小关键帧，而是连续极小关键帧满
     * [sustainMs] 之后。
     */
    var onSustainedBlackScreen: (() -> Unit)? = null

    private var tinyKeyframeStreak = 0
    private var alerted = false

    /**
     * 本轮黑屏 streak 的起点；不在 streak 中时为 null。
     *
     * 用 null 而不是 `0L` 哨兵：原来的内联实现用 `since > 0` 判断，会把"恰好从时钟
     * 0 开始的黑屏"整个吃掉，延迟一整个窗口才升级 —— 真实缺陷。
     */
    private var blackSinceMs: Long? = null
    private var recoveryFired = false

    /** 黑屏持续多久才触发 [onSustainedBlackScreen]。 */
    var sustainMs: Long = BLACK_SCREEN_SUSTAIN_MS

    /**
     * 升级闸门：返回 `true`（解码仍在正常出帧）时**只保留告警位、不升级**
     * （[onSustainedBlackScreen] 不触发）。
     *
     * 为什么需要：纯码流判据分不清「内容本身就是黑」和「管线卡死」——两者都表现为
     * 连续极小 I 帧。桌面端实测（2026-10-17 MEIZU）：VD 内容合法为黑（空 launcher /
     * 手机屏熄灭）时，编码器持续输出极小 I 帧，10s 窗口一到必然误报，每个会话白烧
     * 2 轮重连（~40s，`MAX_BLACK_SCREEN_RECONNECTS`）。
     *
     * 接上「解码是否还在出帧」后：**黑 + 仍在正常解码 = 内容黑，不升级**；
     * **黑 + 解码停摆 = 管线故障，升级**。闸门每帧都会被问一次（只在满窗后），
     * 实现方必须是廉价且非阻塞的。
     *
     * 默认 `null` = 不设闸门 = 纯码流判据的原有行为。
     */
    var decodingAliveCheck: (() -> Boolean)? = null

    /**
     * 喂一帧。
     *
     * @param isKeyFrame 是否 I 帧（NAL type 5）。
     * @param size 载荷字节数。
     * @param nowMs 单调毫秒时钟，可注入以便测试。
     */
    fun onFrame(isKeyFrame: Boolean, size: Int, nowMs: Long = clock()) {
        if (!isKeyFrame) return

        if (size >= keyframeMaxBytes) {
            // 正常大小的 I 帧清空 streak 并重新武装升级。
            alerted = false
            tinyKeyframeStreak = 0
            // 必须写 null，不能写 0L：nowMs 是进程相对时钟（桌面端 nanoTime、
            // 车机端 elapsedRealtime 都是"开机/启动后的毫秒数"，量级数分钟起步），
            // 0L 会让**紧跟着正常帧的那一帧微小关键帧**立即满足 `nowMs - 0 >= sustainMs`
            // ——每次"正常帧 → 单帧微小"都误报"持续黑屏"。真机 2026-10-09 实测：
            // 桌面端日志原话"连续 1 个微小关键帧且已持续 10000ms"，驱动 WIN-07
            // 每 ~14s 一轮的重连风暴，两次自愈预算在没有真故障时就被烧光。
            blackSinceMs = null
            recoveryFired = false
            return
        }

        tinyKeyframeStreak++
        if (blackSinceMs == null) blackSinceMs = nowMs

        if (tinyKeyframeStreak >= alertStreak && !alerted) {
            alerted = true
        }

        if (recoveryFired) return
        val since = blackSinceMs ?: return
        if (nowMs - since >= sustainMs) {
            // 黑屏但解码仍在正常出帧 → 内容本身是黑，不是管线故障：不升级，
            // 保留 blackSinceMs 继续观察（一旦解码停摆，下一帧就会升级）。
            if (decodingAliveCheck?.invoke() == true) return
            recoveryFired = true
            onSustainedBlackScreen?.invoke()
        }
    }

    /**
     * 武装 [onSustainedBlackScreen] 的持续窗口为 [sustainMs]。
     *
     * 会话窗口变化时调用方必须调一次：保持 [sustainMs] 是普通可变字段意味着裸赋值
     * 会静默地让已构造的检测器停在默认值上。
     */
    fun setSustainWindow(ms: Long) {
        sustainMs = ms
    }

    /** 连续极小关键帧达到 [alertStreak] 后为 true。 */
    fun isAlerted(): Boolean = alerted

    /** 升级已触发（直到 [reset]）。 */
    fun hasFiredRecovery(): Boolean = recoveryFired

    /** 当前连续极小关键帧计数。 */
    fun streak(): Int = tinyKeyframeStreak

    /**
     * 为新会话重新武装：新码流得到新机会，"只升级一次"的闩锁不能跨会话存活。
     */
    fun reset() {
        tinyKeyframeStreak = 0
        alerted = false
        blackSinceMs = null
        recoveryFired = false
    }

    companion object {
        const val BLACK_SCREEN_SUSTAIN_MS = 3000L
    }
}
