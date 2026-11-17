package com.dilinkauto.desktop.display

import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 会话期间阻止 Windows 息屏 / 休眠。
 *
 * 原理是 `kernel32!SetThreadExecutionState`：
 *  - `ES_CONTINUOUS` 让设置"持续有效"直到同一线程再次调用；
 *  - `ES_SYSTEM_REQUIRED` 阻止系统休眠；
 *  - `ES_DISPLAY_REQUIRED` 阻止显示器关闭。
 *
 * ── 为什么必须固定在一个专用线程上调用 ──
 * 该 API 是**按线程**生效的：等待状态在调用它的那个线程退出时被系统清掉。
 * 若在 UI 线程（或某个随时可能结束的协程线程）上调用，线程一换/一退，屏幕照样会关。
 * 所以这里用一个常驻的单线程执行器承载所有调用，[close] 之前这个线程一直活着。
 *
 * 失败语义：非 Windows 或 JNA 不可用时退回 [NoopDriver]，[enable] 返回 false，
 * 调用方据此提示"本机不支持"，但不影响镜像主流程。
 */
class KeepAwake(private val driver: Driver = defaultDriver()) {

    /** 平台相关的那一个系统调用；抽出来是为了让判定逻辑可以脱离 Windows 单测。 */
    interface Driver {
        /**
         * 调用 `SetThreadExecutionState`。
         *
         * Win32 原型是 `EXECUTION_STATE SetThreadExecutionState(EXECUTION_STATE)`
         * —— **32 位** 参数与返回值（Windows SDK 里 EXECUTION_STATE 就是 DWORD）。
         * 所以这里按 Int 映射；返回 0 表示失败。
         */
        fun setExecutionState(flags: Int): Int
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeepAwake").apply { isDaemon = true }
    }

    @Volatile
    var isActive: Boolean = false
        private set

    /** 本机是否真的能阻止息屏；非 Windows 或 JNA/内核库不可用时为 false，UI 据此禁用开关。 */
    val isSupported: Boolean get() = driver !== NoopDriver

    /** 请求"保持常亮"。返回 false 表示平台不支持或调用失败。 */
    fun enable(): Boolean = apply(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED, active = true)

    /** 释放常亮请求。返回 false 表示平台不支持或调用失败。 */
    fun disable(): Boolean = apply(ES_CONTINUOUS, active = false)

    private fun apply(flags: Int, active: Boolean): Boolean {
        val ok = try {
            val result = executor.submit<Int> { driver.setExecutionState(flags) }
                .get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result != 0
        } catch (_: Exception) {
            false
        }
        isActive = ok && active
        return ok
    }

    fun close() {
        if (isActive) disable()
        executor.shutdownNow()
    }

    /** 非 Windows，或 JNA/内核库不可用时使用的空驱动。 */
    object NoopDriver : Driver {
        override fun setExecutionState(flags: Int): Int = 0
    }

    companion object {
        /**
         * Win32 EXECUTION_STATE 标志位。
         *
         * 按 Int 映射（audit D-I1）：`0x80000000` 在 Kotlin 里默认是 Long，写成
         * `Int` 需要显式 `.toInt()` —— 这正是此前的映射错误（把 32 位 API 当 64 位
         * 参数传，x64 上"恰好工作"是因为 callee 只读低 32 位）。
         */
        const val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002

        private const val CALL_TIMEOUT_MS = 2_000L

        fun defaultDriver(): Driver {
            val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
            if (!isWindows) return NoopDriver
            return runCatching<Driver> { WindowsDriver() }.getOrElse { NoopDriver }
        }
    }

    private class WindowsDriver : Driver {
        private val kernel32: Kernel32 = Native.load("kernel32", Kernel32::class.java)

        // Int 映射（audit D-I1）：Win32 的 esFlags 是 32 位 DWORD。
        override fun setExecutionState(flags: Int): Int = kernel32.SetThreadExecutionState(flags)

        private interface Kernel32 : Library {
            fun SetThreadExecutionState(esFlags: Int): Int
        }
    }
}
