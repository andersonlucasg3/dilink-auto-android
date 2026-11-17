package com.dilinkauto.protocol

/**
 * 平台日志钩子。
 *
 * :protocol-core 是纯 JVM 模块，不能直接调用 Android 的 android.util.Log，
 * 所以把"输出一条日志"抽象成可替换的 [sink]：
 *  - 桌面端 / 单元测试：默认写到标准输出。
 *  - Android 端：在各进程入口安装为 android.util.Log（见 :protocol 的 AndroidPlatformHooks）。
 */
object PlatformLog {

    /** 日志级别。仅区分当前用到的两档。 */
    enum class Level { DEBUG, WARN }

    /** 日志接收器。默认写标准输出。 */
    @Volatile
    var sink: (level: Level, tag: String, message: String) -> Unit =
        { _, tag, message -> println("[$tag] $message") }

    fun d(tag: String, message: String) = sink(Level.DEBUG, tag, message)

    fun w(tag: String, message: String) = sink(Level.WARN, tag, message)
}