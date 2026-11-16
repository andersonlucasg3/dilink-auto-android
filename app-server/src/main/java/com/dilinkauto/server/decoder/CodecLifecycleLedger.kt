package com.dilinkauto.server.decoder

/**
 * MediaCodec 生命周期台账（issue #205）。
 *
 * 背景：解码器创建打 `MediaCodec created`，释放却没有任何日志 —— 现场 `client.log`
 * 数出「建 5 次、停 4 次」却无法判断第 5 个是泄漏还是日志被清掉（真相是断线瞬间的
 * 行进了离线缓冲，被下个会话 `beginSession` 清掉）。没有 generation 锚点，
 * 「成对」永远只能靠猜。
 *
 * 台账只做三件事，全是纯逻辑（不碰 MediaCodec，可单测）：
 *  - 创建时发一个自增的 generation；
 *  - 释放时销账，重复释放 / 未知 generation 返回 `false` 让调用方打 W；
 *  - 随时能报出仍未释放的数量，供进程退出前对账。
 *
 * 线程模型：创建在调用方线程、释放在喂帧线程，全部 `synchronized(this)`。
 */
internal class CodecLifecycleLedger {

    private var nextGeneration = 1
    private val live = LinkedHashSet<Int>()

    /** 登记一个新解码器，返回它的代号（从 1 起单调递增）。 */
    @Synchronized
    fun onCreated(): Int {
        val generation = nextGeneration++
        live.add(generation)
        return generation
    }

    /**
     * 销账。
     *
     * @return `false` 表示该 generation 不在未释放集合里（重复释放或野代号）。
     */
    @Synchronized
    fun onReleased(generation: Int): Boolean = live.remove(generation)

    /** 仍未释放的解码器数量。 */
    @Synchronized
    fun outstanding(): Int = live.size

    /** 仍未释放的代号，供 W 行直接指出是哪一个。 */
    @Synchronized
    fun liveGenerations(): List<Int> = live.toList()
}

/**
 * 释放解码器的**唯一**出口（issue #205）。
 *
 * 顺序固定为「先 stop 再 release」，两者各自吞异常 —— `stop()` 对未启动的实例会抛
 * `IllegalStateException`，`release()` 对已释放的实例同理，任何一步失败都不能挡住
 * 另一步，更不能让 finally 里的收尾把异常抛回喂帧线程。
 *
 * 用回调而不是 `MediaCodec` 参数：`MediaCodec` 是 final 类无法打桩，抽出回调节点后
 * 单测可以用假解码器驱动，断言「顺序」与「台账销账」。
 */
internal fun releaseCodecQuietly(
    ledger: CodecLifecycleLedger,
    generation: Int,
    stop: () -> Unit,
    release: () -> Unit,
): Boolean {
    try { stop() } catch (_: Exception) {}
    try { release() } catch (_: Exception) {}
    return ledger.onReleased(generation)
}
