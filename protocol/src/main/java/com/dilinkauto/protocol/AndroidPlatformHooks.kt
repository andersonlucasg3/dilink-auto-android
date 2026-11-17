package com.dilinkauto.protocol

import android.os.Process
import android.util.Log

/**
 * 在 Android 进程中安装 :protocol-core 的平台钩子。
 *
 * 必须在每个使用 :protocol 的进程入口调用一次（Application.onCreate / vd-server main），
 * 否则核心模块会退回默认实现：日志走标准输出（Android 上不会被路由到 logcat），
 * 且 I/O 线程只提升 JVM 优先级而非 URGENT_DISPLAY。
 *
 * 幂等：重复调用只是重设同样的实现，无副作用。
 */
object AndroidPlatformHooks {

    fun install() {
        PlatformLog.sink = { level, tag, message ->
            when (level) {
                PlatformLog.Level.DEBUG -> Log.d(tag, message)
                PlatformLog.Level.WARN -> Log.w(tag, message)
            }
        }
        ThreadPriority.elevateCurrent = {
            Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
    }
}