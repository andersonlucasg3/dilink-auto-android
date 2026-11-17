package com.dilinkauto.desktop.config

import java.io.File

/**
 * 桌面端的数据目录与文件位置。
 *
 * 默认 `%APPDATA%\DiLinkAuto`（与 Android 侧 `AppPrefs.FILE_NAME` 同名的目录概念），
 * 非 Windows 或 `APPDATA` 缺失时退回 `~/.DiLinkAuto`。
 *
 * 支持用环境变量 `DILINK_DESKTOP_HOME` 覆盖整个目录——用于便携模式与测试
 * （测试绝不能污染用户真实的 `%APPDATA%`）。
 */
object DesktopPaths {
    const val APP_DIR_NAME = "DiLinkAuto"
    const val CONFIG_FILE_NAME = "config.json"
    const val LOG_FILE_NAME = "desktop.log"

    /** 覆盖用环境变量：设置后所有配置/日志都落到该目录下。 */
    const val HOME_ENV = "DILINK_DESKTOP_HOME"

    fun home(): File {
        val override = System.getenv(HOME_ENV)
        if (!override.isNullOrBlank()) return File(override)
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) File(appData) else File(System.getProperty("user.home"))
        return File(base, APP_DIR_NAME)
    }

    fun configFile(home: File = home()): File = File(home, CONFIG_FILE_NAME)

    fun logFile(home: File = home()): File = File(home, LOG_FILE_NAME)
}
