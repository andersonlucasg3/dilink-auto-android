package com.dilinkauto.vdserver

/**
 * 批量 MOVE 的补丁计划（issue #60）。
 *
 * 车机端可能整段丢掉某根手指的 DOWN（网络丢帧、队列丢弃），此后发来的 MOVE 里
 * 仍然带着这个 pointerId。若不补一条 ACTION_POINTER_DOWN，应用侧永远不认识这根
 * 手指，表现为「多指手势时某一根失效」。
 *
 * 判定与注入分离，是为了让规则能在 JVM 单测里钉住（`TouchBatchPlanTest`）——
 * 真正的 MotionEvent 注入依赖 Android 运行时，离线测不了。
 */
internal object TouchBatchPlan {

    /**
     * @param active 当前已按下（收到过 DOWN）的 pointerId 集合。
     * @param moving 本批 MOVE 报文里出现的 pointerId（按报文中出现顺序）。
     * @param maxPointers 协议允许的指针上限；越界 id 一律忽略。
     * @return 需要先补一条 ACTION_POINTER_DOWN 的 id，顺序与 [moving] 一致、已去重。
     */
    fun pointersMissingDown(active: Collection<Int>, moving: List<Int>, maxPointers: Int): List<Int> {
        val missing = LinkedHashSet<Int>()
        for (id in moving) {
            if (id !in 0 until maxPointers) continue
            if (id !in active) missing += id
        }
        return missing.toList()
    }
}
