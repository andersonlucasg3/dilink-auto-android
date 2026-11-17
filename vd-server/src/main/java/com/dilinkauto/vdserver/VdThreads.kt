package com.dilinkauto.vdserver

/**
 * 守护线程工厂函数，消除散落的匿名守护线程样板代码。
 *
 * @param name 线程名称
 * @param start 是否在创建后立即启动，默认为 true
 * @param block 线程执行体
 */
internal fun daemonThread(name: String, start: Boolean = true, block: () -> Unit): Thread =
    Thread(Runnable { block() }, name).apply {
        isDaemon = true
        if (start) start()
    }
