package com.dilinkauto.protocol

/**
 * I/O 线程优先级钩子。
 *
 * 原实现直接调用 android.os.Process.setThreadPriority(URGENT_DISPLAY)：车机是 8x A53 无大核，
 * 需要把 socket 读取线程提到最高优先级，避免被同一线程池上的后台协程饿死。
 * :protocol-core 不能依赖 Android API，故抽象为可替换钩子：
 *  - 默认（桌面 / 单元测试）：提升为 JVM 线程最高优先级。
 *  - Android：安装为 Process.setThreadPriority(Process.myTid(), THREAD_PRIORITY_URGENT_DISPLAY)。
 */
object ThreadPriority {

    /** 把当前线程提升为高优先级。 */
    @Volatile
    var elevateCurrent: () -> Unit = {
        Thread.currentThread().priority = Thread.MAX_PRIORITY
    }
}