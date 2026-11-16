package com.dilinkauto.server.service

/**
 * 「这次连接是谁发起的」（issue #201）。
 *
 * 分成两类是本次修复的核心：
 *  - [auto] = true：设备/服务自己发现的时机 —— USB 重新枚举、ADB 就绪、服务 START、重试回环、
 *    网络恢复、网关/mDNS 探测。用户点过「断开」之后，这些入口**一律不得**再发起连接。
 *  - [auto] = false：只来自用户显式操作 —— 重连按钮、手填 IP、外部显式 CONNECT intent。
 *    只有它们能清除 [CarPrefs.userDisconnected]。
 *
 * 缺陷形态（issue #201）：`userDisconnected` 原先只在重试回环一处生效，而
 * `onAdbReady` / `ACTION_START` / USB attach 都会绕到 `startConnection()`，
 * 后者又无条件把标志清回 false —— 于是「说好不重连」的 3~13s 后自己连上。
 */
internal enum class ConnectTrigger(val auto: Boolean, val label: String) {
    USER_RECONNECT(auto = false, label = "user reconnect"),
    USER_MANUAL_IP(auto = false, label = "user manual IP"),
    USER_EXPLICIT_CONNECT(auto = false, label = "explicit ACTION_CONNECT"),

    AUTO_ADB_READY(auto = true, label = "ADB ready"),
    AUTO_SERVICE_START(auto = true, label = "service ACTION_START"),
    AUTO_RETRY(auto = true, label = "service retry"),
    AUTO_NETWORK_AVAILABLE(auto = true, label = "network available"),
    AUTO_DISCOVERY(auto = true, label = "gateway/mDNS discovery"),
}

/** 抑制原因；[logText] 直接拼进 `[Connect] auto-reconnect suppressed (...)` 那一行。 */
internal enum class ReconnectSuppressed(val logText: String) {
    USER_DISCONNECTED("userDisconnected"),
    COOLDOWN("cooldown"),
}

/**
 * 闸门判定结果。
 *
 * [clearUserDisconnected] 只在用户显式连接时为 true —— 自动入口即使放行（正常情况下
 * 标志本来就是 false）也**不许**改写用户意图。
 */
internal data class ReconnectDecision(
    val allowed: Boolean,
    val clearUserDisconnected: Boolean,
    val suppressed: ReconnectSuppressed? = null,
)

/**
 * 「是否允许发起连接」的纯函数（issue #201）。
 *
 * 所有入口（`CarSessionCoordinator.startConnection` / `beginConnect` / `startWifiTrack`、
 * `CarAdbTracker` 的 USB/TCP 探测、网络恢复回调）共用这一个判定，避免各写一份而漂移。
 * 输入只有三个：触发来源、`userDisconnected` 标志、是否仍在用户断开后的冷却窗口内。
 */
internal object AutoReconnectPolicy {

    /**
     * 用户断开后的自动重连冷却窗口。
     *
     * 标志本身已经能挡住自动入口，冷却期是兜底：万一标志被旧版本/外部写脏，或进程重启后
     * 只剩内存态的瞬间，3~13s 内那些 ADB 重枚举也不会把会话拉起来。用户点「重新连接」
     * 会同时清掉标志与冷却，不受此约束。
     */
    const val USER_DISCONNECT_COOLDOWN_MS = 15_000L

    /** 连接决策日志的统一前缀：`beginSession` 靠它把跨会话线索保留下来。 */
    const val LOG_MARKER = "[Connect]"

    fun decide(
        trigger: ConnectTrigger,
        userDisconnected: Boolean,
        inCooldown: Boolean,
    ): ReconnectDecision = when {
        // 用户显式操作永远放行，并解除抑制状态。
        !trigger.auto -> ReconnectDecision(allowed = true, clearUserDisconnected = true)
        // 自动入口：用户的「不重连」优先。
        userDisconnected -> ReconnectDecision(
            allowed = false,
            clearUserDisconnected = false,
            suppressed = ReconnectSuppressed.USER_DISCONNECTED,
        )
        inCooldown -> ReconnectDecision(
            allowed = false,
            clearUserDisconnected = false,
            suppressed = ReconnectSuppressed.COOLDOWN,
        )
        else -> ReconnectDecision(allowed = true, clearUserDisconnected = false)
    }
}
