package com.dilinkauto.desktop

import androidx.compose.ui.window.application
import com.dilinkauto.desktop.config.DesktopPaths
import com.dilinkauto.desktop.config.DesktopSettings
import com.dilinkauto.desktop.config.DesktopSettingsStore
import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.desktop.ui.DesktopWindow
import com.dilinkauto.protocol.HandshakeResponse
import com.dilinkauto.protocol.PlatformLog

/**
 * :app-desktop 入口（只做参数解析、配置装配与选择运行模式）。
 *
 * 两种模式：
 *  - 默认：打开桌面窗口显示手机镜像；
 *  - `--probe`：无窗口探针，只打印状态机与视频帧率（回归/联调工具）。
 *
 * 用法： app-desktop [手机IP] [宽=1280] [高=720] [--probe]
 *
 * 手机 IP 优先取命令行；没传则用 `config.json` 的 `dev_phone_ip`。
 * 配置与日志目录见 [DesktopPaths]（默认 `%APPDATA%\DiLinkAuto`，可用环境变量
 * `DILINK_DESKTOP_HOME` 覆盖，便携模式与测试都靠它）。
 *
 * 分工：连接编排在 [DesktopConnectionService]，握手报文在 [HandshakeFactory]，
 * 解码在 [com.dilinkauto.desktop.video.VideoDecodePipeline]，渲染在
 * [com.dilinkauto.desktop.ui.SwingVideoView]，应用列表在
 * [com.dilinkauto.desktop.apps.AppCatalog]，窗口模式的编排在 [DesktopApp]。
 */
fun main(args: Array<String>) {
    val probe = args.contains("--probe")
    val positional = args.filterNot { it.startsWith("--") }

    // 1) 配置：先读 config.json（首次运行落一份默认配置，方便用户直接编辑）。
    val configFile = DesktopPaths.configFile()
    val settingsStore = DesktopSettingsStore(configFile)
    val settings = settingsStore.load { println("[config] $it") }
    if (!configFile.isFile) {
        settingsStore.save(settings) { println("[config] $it") }
    }

    // 2) 日志：控制台始终输出，文件写入由 log_enabled 控制。
    val log = DesktopLog(file = DesktopPaths.logFile(), fileEnabled = settings.logEnabled)
    installDesktopLogging(log)
    log.info(TAG, "config: ${configFile.absolutePath} (log_enabled=${settings.logEnabled})")

    // 3) 手机 IP：命令行 > config.json，都没有就给用法提示。
    val host = positional.getOrNull(0)?.takeIf { it.isNotBlank() }
        ?: settings.devPhoneIp.ifBlank { null }
    if (host == null) {
        val usage = "用法: app-desktop [手机IP] [宽=1280] [高=720] [--probe]\n" +
            "也可在 ${configFile.absolutePath} 里填写 \"dev_phone_ip\"。"
        println(usage)
        log.info(TAG, "缺少手机 IP，已退出")
        log.close()
        return
    }

    val width = positional.getOrNull(1)?.toIntOrNull() ?: DEFAULT_VIEWPORT_WIDTH
    val height = positional.getOrNull(2)?.toIntOrNull() ?: DEFAULT_VIEWPORT_HEIGHT

    val config = DesktopConfig(
        phoneHost = host,
        viewportWidth = width,
        viewportHeight = height,
        targetFps = settings.startupFps,
        bitrate = settings.startupBitrate,
        dpiOverride = settings.startupDpi,
    )
    log.info(
        TAG,
        "连接目标 ${config.phoneHost} 视口 ${width}x$height fps=${config.targetFps} " +
            "bitrate=${config.bitrate} dpiOverride=${config.dpiOverride} hwaccel=${settings.startupHwaccel}",
    )

    try {
        if (probe) ProbeRunner(config, log).run() else runWindow(config, settings, configFile, log)
    } finally {
        log.close()
    }
}

/** 窗口模式：把编排交给 [DesktopApp]，窗口关闭后收尾退出。 */
private fun runWindow(
    config: DesktopConfig,
    settings: DesktopSettings,
    configFile: java.io.File,
    log: DesktopLog,
) {
    val app = DesktopApp(configFile, settings, log, config)
    app.start()

    // exitProcessOnExit=false：窗口关闭后先回到这里做收尾（停会话触发手机回收 VD、停解码线程），
    // 再显式退出进程；否则 Compose 会直接 exitProcess，收尾代码永远执行不到。
    application(exitProcessOnExit = false) {
        DesktopWindow(app = app, onClose = { exitApplication() })
    }

    app.stop()
    log.info(TAG, "已退出")
    kotlin.system.exitProcess(0)
}

internal fun logHandshake(log: DesktopLog, r: HandshakeResponse) {
    log.info(
        "handshake",
        "${r.deviceName} accepted=${r.accepted} display=${r.displayWidth}x${r.displayHeight} " +
            "vdId=${r.virtualDisplayId} method=${r.connectionMethod} vdDpi=${r.vdDpi} " +
            "adbPort=${r.adbPort}",
    )
}

/**
 * 桌面端平台日志策略：只把 WARN 及以上转给 [DesktopLog]。
 *
 * `:protocol-core` 的 NioReader 每次轮询无数据都会打 DEBUG 日志
 * （真机 17 秒上百行），会淹没真正有用的会话日志。会话级日志由
 * [DesktopConnectionService.onLog] 单独输出，不经过这里。
 */
private fun installDesktopLogging(log: DesktopLog) {
    PlatformLog.sink = { level, tag, message ->
        if (level == PlatformLog.Level.WARN) log.warn(tag, message)
    }
}

private const val TAG = "main"
private const val DEFAULT_VIEWPORT_WIDTH = 1280
private const val DEFAULT_VIEWPORT_HEIGHT = 720
