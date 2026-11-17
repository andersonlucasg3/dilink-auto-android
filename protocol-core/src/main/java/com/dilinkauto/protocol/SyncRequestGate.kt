package com.dilinkauto.protocol

/**
 * IDR 补帧请求闸门：**滑动窗口内限次**（issue #75 的单一来源，issue #163 改窗口语义）。
 *
 * 车机（app-server）与手机（vd-server）各有一道闸门，两端必须用同一个状态机，否则
 * 「车机放行、手机吞掉」的死区会回来（issue #75：两端 500ms / 1000ms 不一致，导致
 * 距上次授予 500–999ms 的请求白跑一趟，只能干等自然 GOP）。
 *
 * 旧实现是「距上次放行 ≥ [VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS]」的硬性间隔，
 * 等价于窗口内只放行 1 次：第 8 次重同步恢复 305ms 后再次断链时，第二次请求被直接吞掉，
 * 车机只能等 3s 的自然 IDR，跳过 87 个 P 帧、冻结 2894ms（issue #163 现场日志）。
 * 改成「[windowMs] 内最多放行 [maxPerWindow] 次」后，二次缺口能被及时补给，同时窗口内
 * 仍限次，不会放开 issue #70 / #55 担心的 IDR 风暴。
 *
 * 状态是不可变列表，调用方只需要持有最新实例（车机用 `AtomicReference` + CAS，
 * 手机在管线单线程内直接赋值），判定本身是纯函数，便于脱机单测。
 *
 * @property windowMs 滑动窗口长度（毫秒）。
 * @property maxPerWindow 窗口内允许放行的最大次数。
 * @property grantsMs 窗口内已放行的请求时刻（`elapsedRealtime` 毫秒）。按 `tryAcquire` 的调用
 *   时序依次追加，故天然按时间戳升序；长度 ≤ [maxPerWindow]。
 */
data class SyncRequestGate(
    val windowMs: Long = VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS,
    val maxPerWindow: Int = VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW,
    val grantsMs: List<Long> = emptyList(),
) {
    /**
     * 尝试在 [nowMs] 放行一次请求。纯函数：无论放行还是拒绝都不修改自身。
     *
     * @return 放行时返回**新状态**（调用方需用它替换旧状态）；窗口已满时返回 `null`。
     */
    fun tryAcquire(nowMs: Long): SyncRequestGate? {
        val inWindow = grantsMs.filter { nowMs - it < windowMs }
        if (inWindow.size >= maxPerWindow) return null
        return copy(grantsMs = inWindow + nowMs)
    }
}
