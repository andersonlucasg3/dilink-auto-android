package com.dilinkauto.server.service

/**
 * 车机视口变化的重握手门（issue #22）。
 *
 * 旋转/分屏拖动期间 `MainActivity.onConfigurationChanged` 会连续上报多个尺寸，
 * 而每次重握手都要拆掉旧 VD、重新部署引擎，耗时数秒。本类持有这段判定：
 * 不依赖 Android，也不自己起线程/协程 —— 定时、执行、在飞判定全部由调用方注入，
 * 因此可以用假调度器直接做单测。
 *
 * 三条规则：
 * 1. 会话不在 STREAMING/CONNECTED，或尺寸与当前已生效尺寸相同 → 忽略；
 * 2. 窗口内的连续尺寸只保留最后一个，静默 [debounceMs] 后才发一次重握手；
 * 3. 重握手进行中收到的新尺寸先记下，等这一次结束后按最新尺寸补跑一次。
 */
internal class ViewportRehandshakeGate(
    private val debounceMs: Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Cancelable,
    private val request: (Target) -> Unit,
    private val isInFlight: () -> Boolean,
    private val log: (String) -> Unit = {},
) {

    /** 一次重握手的目标尺寸。 */
    data class Target(val width: Int, val height: Int, val dpi: Int)

    /** 可取消的定时任务；生产环境包一个协程 Job，测试里是假任务。 */
    fun interface Cancelable {
        fun cancel()
    }

    private val lock = Any()

    /** 去抖窗口内最后上报的尺寸；被 [fire] 消费或会话结束前一直保留。 */
    private var pending: Target? = null

    /** 当前去抖定时器；新尺寸到来时取消并重排。 */
    private var timer: Cancelable? = null

    /**
     * 收到一次视口上报。
     *
     * [appliedWidth]/[appliedHeight] 是当前真正生效的编码尺寸（服务里的 `vdWidth`/
     * `vdHeight`），[sessionReady] 表示会话仍在 STREAMING/CONNECTED。
     */
    fun onViewportChanged(
        candidate: Target,
        appliedWidth: Int,
        appliedHeight: Int,
        sessionReady: Boolean,
    ) {
        if (!sessionReady) return
        if (candidate.width == appliedWidth && candidate.height == appliedHeight) return
        synchronized(lock) {
            // 同一目标重复上报时不重置窗口，避免旋转动画把静默期无限延长。
            if (pending == candidate) return
            pending = candidate
            timer?.cancel()
            timer = schedule(debounceMs) { fire() }
        }
        log("Viewport ${candidate.width}x${candidate.height}@${candidate.dpi} queued (${debounceMs}ms debounce)")
    }

    /**
     * 一次重握手结束时调用（服务在 `rehandshakeOnExistingControl` 的 finally 里调）。
     * 若期间积压了更新的尺寸，立即补跑一次；没有积压则什么都不做。
     */
    fun onRehandshakeFinished() {
        val queued = synchronized(lock) { pending } ?: return
        log("Re-handshake finished — replaying queued ${queued.width}x${queued.height}")
        fire()
    }

    /**
     * 会话重建/断开时清空排队：上一会话留下的尺寸不能在新会话里补跑。
     */
    fun reset() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
            pending = null
        }
    }

    private fun fire() {
        val target: Target
        synchronized(lock) {
            timer = null
            target = pending ?: return
            if (isInFlight()) {
                // 保留 pending：当前这一次结束时 onRehandshakeFinished() 会补跑。
                log("Re-handshake still in flight — deferring ${target.width}x${target.height}")
                return
            }
            pending = null
        }
        request(target)
    }
}
