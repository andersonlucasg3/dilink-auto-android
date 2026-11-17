package com.dilinkauto.protocol

/**
 * 通用参数化指数退避策略计算器。
 *
 * 核心模型：在 [escalateAfter] 次尝试之前保持基线 [baseMs]；之后每次失败翻倍，
 * 直到 [maxDoublings] 次翻倍或 [maxMs] 封顶。
 *
 * 公式：
 *   if (attempts <= escalateAfter) baseMs
 *   else {
 *       val doublings = (attempts - escalateAfter).coerceAtMost(maxDoublings.toLong())
 *       (baseMs shl doublings.toInt()).coerceAtMost(maxMs)
 *   }
 */
data class BackoffPolicy(
    val baseMs: Long = DEFAULT_BASE_MS,
    val maxMs: Long = DEFAULT_MAX_MS,
    val escalateAfter: Long = DEFAULT_ESCALATE_AFTER,
    val maxDoublings: Int = DEFAULT_MAX_DOUBLINGS,
) {
    val initialMs: Long get() = baseMs

    init {
        require(baseMs > 0) { "baseMs must be positive: $baseMs" }
        require(maxMs >= baseMs) { "maxMs must be >= baseMs: $maxMs < $baseMs" }
        require(escalateAfter >= 0) { "escalateAfter must be non-negative: $escalateAfter" }
        require(maxDoublings >= 0) { "maxDoublings must be non-negative: $maxDoublings" }
    }

    /**
     * 计算第 [attempts] 次连续失败/重试时的退避等待毫秒数。
     * [attempts] <= [escalateAfter] 时返回 [baseMs]。
     */
    fun compute(attempts: Long): Long = exponential(
        step = attempts,
        baseMs = baseMs,
        maxMs = maxMs,
        maxDoublings = maxDoublings,
        escalateAfter = escalateAfter
    )

    fun compute(attempts: Int): Long = compute(attempts.toLong())

    operator fun invoke(attempts: Long): Long = compute(attempts)

    operator fun invoke(attempts: Int): Long = compute(attempts.toLong())

    companion object {
        const val DEFAULT_BASE_MS = 500L
        const val DEFAULT_INITIAL_MS = DEFAULT_BASE_MS
        const val DEFAULT_MAX_MS = 8_000L
        const val DEFAULT_ESCALATE_AFTER = 1L
        const val DEFAULT_MAX_DOUBLINGS = 4

        /** 默认退避策略（500ms 起步，>1 开始翻倍，最多 4 次翻倍到 8000ms 封顶，匹配车机端重连策略） */
        val DEFAULT = BackoffPolicy()

        /**
         * 桌面端重试/重建策略（前 3 次保持 500ms 容忍偶发抖动，>3 开始翻倍，最多 4 次翻倍到 8000ms 封顶）
         */
        val TOLERANT = BackoffPolicy(escalateAfter = 3L)

        /**
         * 便捷静态计算方法。
         */
        @JvmStatic
        fun exponential(
            step: Long,
            baseMs: Long = DEFAULT_BASE_MS,
            maxMs: Long = DEFAULT_MAX_MS,
            maxDoublings: Int = DEFAULT_MAX_DOUBLINGS,
            escalateAfter: Long = DEFAULT_ESCALATE_AFTER,
        ): Long {
            if (step <= escalateAfter) return baseMs
            val doublings = (step - escalateAfter).coerceAtMost(maxDoublings.toLong())
            return (baseMs shl doublings.toInt()).coerceAtMost(maxMs)
        }

        @JvmStatic
        fun exponential(
            step: Int,
            baseMs: Long = DEFAULT_BASE_MS,
            maxMs: Long = DEFAULT_MAX_MS,
            maxDoublings: Int = DEFAULT_MAX_DOUBLINGS,
            escalateAfter: Long = DEFAULT_ESCALATE_AFTER,
        ): Long = exponential(step.toLong(), baseMs, maxMs, maxDoublings, escalateAfter)
    }
}
