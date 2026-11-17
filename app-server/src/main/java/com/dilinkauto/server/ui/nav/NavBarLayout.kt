package com.dilinkauto.server.ui.nav

/**
 * 导航栏按键的稳定身份，与显示顺序无关（issue #152-PA 追加 [Recent]）。
 *
 * 抽到独立文件并改为 `internal`，是为了让「有哪些键、按什么顺序、什么时候可用」
 * 变成可在 JVM 单测里钉死的纯数据（同 `internal object CarViewport` 的手法）。
 */
internal enum class NavAction { Home, Back, Recent, Eject }

/**
 * 导航栏的按键集合与可用性策略（issue #152-PA）。
 *
 * 横屏左栏自顶向下 `Home / Back / Recent / Eject`；竖屏底栏取严格逆序
 * `Eject / Recent / Back / Home`，保证旋转后每个键仍落在同一物理侧
 * （沿用 issue #152 之前的既有约定，只是多了一个 [NavAction.Recent]）。
 */
internal object NavBarLayout {

    private val RAIL_ORDER = listOf(
        NavAction.Home,
        NavAction.Back,
        NavAction.Recent,
        NavAction.Eject
    )

    /**
     * @param reverse 竖屏底栏传 `true`：返回 rail 顺序的镜像。
     */
    fun actions(reverse: Boolean): List<NavAction> =
        if (reverse) RAIL_ORDER.reversed() else RAIL_ORDER

    /**
     * 是否可点击。
     *
     * 只有新增的 [NavAction.Recent] 要求真正连上（[connected]，即 `STREAMING`/`CONNECTED`）：
     * 旋转重握手期间 `state == CONNECTING`，此时 `GO_RECENT` 会落到一个正在拆掉的
     * input 连接上，点了只会刷 `dropped` 警告（`CarSessionCoordinator` 的 sendCommandToVd）。
     * `Home`/`Back`/`Eject` 维持原有行为，不引入新的不可用窗口 —— 尤其 `Eject` 必须在
     * 重连过程中也能中止会话。
     */
    fun isEnabled(action: NavAction, connected: Boolean): Boolean =
        action != NavAction.Recent || connected
}
