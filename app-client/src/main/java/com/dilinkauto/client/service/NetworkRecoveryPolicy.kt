package com.dilinkauto.client.service

/**
 * 「lost 抖动后到底该报什么」的纯判据（issue #204）。
 *
 * 现场（`.tmp/run-1116/client.log`）：
 * ```
 * 16:36:09.248 [ClientNetworkMonitor] Network available: 122
 * 16:36:37.801 [ClientNetworkMonitor] Network lost: 122 — debouncing before reacting
 * 16:36:42.403 [ConnectionService] Car connected (control, session #3)   ← 链路其实是通的
 * ```
 * `onLost` 之后系统再没投递过 `onAvailable`，而 [ClientNetworkMonitor] 的全部状态变化都只靠
 * 回调驱动、debounce 到期无条件确认 lost，于是状态永久停在 lost —— 与「会话照常建立」直接矛盾。
 *
 * 这里把状态机与外部世界解耦成纯函数：调用方负责取时间线事件与 debounce 到期时的复核结果
 * （`ConnectivityManager.getNetworkCapabilities`），判据只回答一个问题 —— 该补一条 available，
 * 还是确认 lost。
 */
class NetworkRecoveryPolicy(
    /** 抖动窗口；与 [ClientNetworkMonitor.DEFAULT_DEBOUNCE_MS] 一致。 */
    private val debounceDelayMs: Long = DEBOUNCE_MS,
) {

    enum class Link { UNKNOWN, AVAILABLE, LOST }

    /**
     * debounce 到期时对系统的复核结果。
     *
     * @param wifiNetId `ConnectivityManager` 里仍然可用的 Wi-Fi 网络标识（`Network{<netId>}` 里的
     *   数字串，见 `ClientNetworkMonitor.netIdOf`）；null = 系统认为没有可用 Wi-Fi。
     */
    data class Recheck(val wifiNetId: String?) {
        val usable: Boolean get() = wifiNetId != null
    }

    enum class Decision {
        /** 复核后链路确实不可用 —— 维持 lost，允许调用方走原有断开流程。 */
        CONFIRM_LOST,

        /** 复核后链路可用 —— 回调没来也必须补一条 available。 */
        REPORT_AVAILABLE,

        /** 没有 pending lost（期间回调已补到 available），什么都不做。 */
        NONE,
    }

    private var link = Link.UNKNOWN
    private var pendingNetId: String? = null
    private var debounceDeadlineMs = 0L

    /** 当前链路状态（只用于日志，不参与重建判据 —— 那个在 [NetworkRebuildPolicy]）。 */
    val state: Link get() = link

    /** 正在 debounce 的网络标识；null = 没有待确认的 lost。 */
    val pendingLostNetId: String? get() = pendingNetId

    /** 系统回调 `onAvailable`（或复核补齐）—— 链路可用。 */
    @Synchronized
    fun onAvailable() {
        link = Link.AVAILABLE
        pendingNetId = null
        debounceDeadlineMs = 0L
    }

    /**
     * 系统回调 `onLost`。
     *
     * @param netId 网络标识（`Network.toString()` 里的 netId）
     * @return true = 需要起 debounce；false = 同一网络的重复 lost（已经在 lost，不再重开窗口）。
     */
    @Synchronized
    fun onLost(netId: String, nowMs: Long): Boolean {
        if (link == Link.LOST && pendingNetId == netId) return false
        link = Link.LOST
        pendingNetId = netId
        debounceDeadlineMs = nowMs + debounceDelayMs
        return true
    }

    /**
     * 真实业务流量自证链路可用（issue #204 方案 B：握手成功）。
     *
     * @return true = 状态从非 available 变成 available（调用方应打一行 available）。
     */
    @Synchronized
    fun onTrafficConfirmed(): Boolean {
        if (link == Link.AVAILABLE && pendingNetId == null) return false
        onAvailable()
        return true
    }

    /**
     * debounce 到期：用复核结果决定报 available 还是确认 lost。
     *
     * 关键点（issue #204）：`onAvailable` 不保证会再来一次。系统若没重发回调，原来的实现
     * 会让状态永久停在 lost（日志也永久静默），而链路其实早就通了。所以这里以复核结果为准：
     * 复核可用 → 主动补一条 available；复核不可用 → 维持 lost，交给调用方走原有断开流程。
     *
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime()`），只做差值比较
     */
    @Synchronized
    fun onDebounceExpired(nowMs: Long, recheck: Recheck): Decision {
        if (link != Link.LOST) return Decision.NONE
        if (nowMs < debounceDeadlineMs) return Decision.NONE
        if (!recheck.usable) return Decision.CONFIRM_LOST
        onAvailable()
        return Decision.REPORT_AVAILABLE
    }

    companion object {
        /** 3s：Wi-Fi 重关联抖动窗口（[ClientNetworkMonitor.DEFAULT_DEBOUNCE_MS]）。 */
        const val DEBOUNCE_MS = 3_000L
    }
}
