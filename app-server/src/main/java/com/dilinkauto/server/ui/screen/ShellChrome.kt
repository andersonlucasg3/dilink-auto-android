package com.dilinkauto.server.ui.screen

/** 信息 chip（时间 / 海拔 / 天气）的落位，见 issue #54。 */
enum class InfoPlacement {
    /** 横屏：左侧常驻导航栏内，与三个按键同栏。 */
    LEFT_RAIL,

    /** 竖屏：内容区顶部信息条。 */
    TOP_BAR
}

/**
 * 车机壳层常驻信息的落位策略（issue #54）。
 *
 * 刻意**不**接收 [Screen]：这三个 chip 属于常驻 chrome，不得随 HOME/APP 切换而消失。
 * 之前 `CarShell` 用 `currentScreen == Screen.HOME` 作闸门，点开音乐进入镜像页后整条
 * 信息条被移除；把判定收敛到这里并用单测锁死，避免以后又被加回页面维度的条件。
 */
object ShellChrome {

    fun infoPlacement(isLandscape: Boolean): InfoPlacement =
        if (isLandscape) InfoPlacement.LEFT_RAIL else InfoPlacement.TOP_BAR

    /** 竖屏顶部信息条是否渲染：只与方向和 chip 是否为空有关。 */
    fun showTopInfoBar(isLandscape: Boolean, chipsEmpty: Boolean): Boolean =
        !isLandscape && !chipsEmpty
}
