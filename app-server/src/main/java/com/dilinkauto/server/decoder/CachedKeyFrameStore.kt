package com.dilinkauto.server.decoder

/**
 * 停播期缓存 IDR 的唯一容器（issue #216 / #205）。
 *
 * 背景：缓存以前是裸 `ByteArray`，没有 codec 代次；新 codec 冷启动会把它当参考帧喂入，
 * `awaitingIdr` 被「参考帧已就位」骗过，新流第一批 P 帧解成空帧/花屏（现场约 9s）。
 *
 * 这里把「写入 / 取出 / 作废」收敛成加锁的原子操作，同时解决 issue #205 留下的
 * 读-清-记账竞态：CONFIG 与喂帧线程并发清缓存时，只有一个调用能看到旧条目，
 * 不会对同一帧记两次 drop。
 *
 * 代次判据：只有 `generation > 0 && generation == currentGeneration` 才可复用 ——
 * 即同一个 codec 实例（`switchSurface` 不换 codec 的语义）。停播期入缓存时没有活着的
 * codec（代次 0），或在 stop 卡死时残留旧代次，新 codec 起来后必然不同代。
 */
internal class CachedKeyFrameStore {

    internal data class Entry(val generation: Int, val data: ByteArray) {
        /** 本条目能否作为 [currentGeneration] 这个 codec 实例的参考帧。 */
        fun isReusableBy(currentGeneration: Int): Boolean =
            generation > 0 && generation == currentGeneration
    }

    /** [take] 的判决结果，调用方据此决定补喂、作废或什么都不做。 */
    internal sealed interface TakeResult {
        /** 同一 codec 代次，可以当参考帧。 */
        data class Reusable(val entry: Entry) : TakeResult

        /** 跨代 / 无有效代次，必须作废，由调用方平账后走 awaitingIdr。 */
        data class Discarded(val entry: Entry) : TakeResult

        /** 没有缓存。 */
        object Empty : TakeResult
    }

    private var entry: Entry? = null

    /**
     * 写入最新 IDR；返回被覆盖的旧条目（没有则 `null`）。
     *
     * 入缓存时必须带**当前 codec 代次**，否则新一代 codec 无法判断它是否可用。
     */
    @Synchronized
    fun replace(generation: Int, data: ByteArray): Entry? {
        val previous = entry
        entry = Entry(generation, data)
        return previous
    }

    /** 是否还有待补喂的缓存（与 [take] / [clear] 互斥，供 `start()` 播种记账）。 */
    @Synchronized
    fun hasEntry(): Boolean = entry != null

    /** 原子地取出并清空缓存，按 [currentGeneration] 判决是否可复用。 */
    @Synchronized
    fun take(currentGeneration: Int): TakeResult {
        val taken = entry ?: return TakeResult.Empty
        entry = null
        return if (taken.isReusableBy(currentGeneration)) {
            TakeResult.Reusable(taken)
        } else {
            TakeResult.Discarded(taken)
        }
    }

    /** 新码流边界（新 CONFIG）或 `stop()`：清空缓存，返回是否真的清掉了条目。 */
    @Synchronized
    fun clear(): Boolean {
        val existed = entry != null
        entry = null
        return existed
    }
}
