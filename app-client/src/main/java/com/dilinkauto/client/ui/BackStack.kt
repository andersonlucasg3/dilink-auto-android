package com.dilinkauto.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 顶层页面的最小返回栈（issue #68）。
 *
 * 只服务 MainActivity 的扁平四页路由：不引入 Navigation-Compose，
 * 只保证「进入子页 → 系统返回/箭头返回」回到上一级，而不是 Activity#finish()
 * 或跳回写死的 Main。Main 自己的系统返回仍交给 Activity 默认实现（退出 App）。
 */
internal class BackStack<T>(initial: T) {

    private val history = ArrayDeque<T>()

    var current: T by mutableStateOf(initial)
        private set

    /** 压入上一页并切到 [next]；重复进入当前页不产生历史。 */
    fun navigate(next: T) {
        if (next == current) return
        history.addLast(current)
        current = next
    }

    /** 返回上一页；栈空返回 false，由调用方决定是否 finish。 */
    fun back(): Boolean {
        if (history.isEmpty()) return false
        current = history.removeLast()
        return true
    }

    /** 重置到 [to] 并清空历史（onboarding 完成、进程重建等）。 */
    fun reset(to: T) {
        history.clear()
        current = to
    }
}
