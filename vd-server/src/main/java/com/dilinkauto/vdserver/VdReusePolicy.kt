package com.dilinkauto.vdserver

/**
 * 本进程已持有的 VD 快照（issue #202 B）。
 *
 * [alive] 必须是**真实的存活判定**（`Display` 仍然有效），不能只看 `virtualDisplay != null`：
 * `VirtualDisplay.release()` 之后对象引用还在，但 display 已经销毁。issue #202 要求
 * "不能复用『已销毁但对象还在』的 VD"。
 */
internal data class VdSnapshot(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val alive: Boolean
)

/** 复用判定的结论 + 一句可进日志的原因（issue #202 B）。 */
internal data class VdReuseDecision(
    val reuse: Boolean,
    val reason: String
)

/**
 * VD 复用判定（issue #202 B）。
 *
 * 背景：issue #202 现场每次重连都重建虚拟显示（id 2→3→…→7），已运行的 APP 先被搬回
 * `display 0` 再被搬回新 VD。判定抽成纯函数，让"什么时候可以不重建"可脱机测试，
 * 也避免调用点各自写一套条件。
 *
 * 复用条件（全部满足）：
 *  1. 本进程已经有一个 VD（[current] != null）；
 *  2. 它的 display 仍然存活（[VdSnapshot.alive]）；
 *  3. 输出 surface 仍然有效（由调用方注入，`Surface.isValid`）；
 *  4. 尺寸与 DPI 与本次目标**完全一致**。
 *
 * 任一条不满足都重建，并把原因写清楚（参数漂移时把旧/新值一起打出来）。
 */
internal object VdReusePolicy {

    fun decide(current: VdSnapshot?, target: VdSnapshot, surfaceValid: Boolean): VdReuseDecision {
        if (current == null) return VdReuseDecision(false, "本进程还没有 VD")
        if (!current.alive) {
            return VdReuseDecision(false, "旧 VD display=${current.displayId} 已销毁（不能复用残留对象）")
        }
        if (!surfaceValid) {
            return VdReuseDecision(false, "输出 surface 已失效（旧 VD display=${current.displayId}）")
        }
        if (current.width != target.width || current.height != target.height || current.dpi != target.dpi) {
            return VdReuseDecision(
                false,
                "参数漂移：旧 ${current.width}x${current.height}@${current.dpi}dpi → " +
                    "新 ${target.width}x${target.height}@${target.dpi}dpi"
            )
        }
        return VdReuseDecision(true, "display=${current.displayId} 仍存活且参数一致")
    }
}
