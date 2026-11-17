package com.dilinkauto.client.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级初始化守卫（2026-10-09 真机崩溃回归）。
 *
 * 背景：DRY-6 重构（db58257）把 wake lock / 通知 / 资产部署抽成独立类之后，
 * [ConnectionService.onCreate] 对两个字段的"赋值时机"与"访问时机"脱钩，真机上
 * 连续踩了两个坑（JVM 单测观测不到，Service 生命周期需要真机）：
 *
 * | 字段 | 崩溃形态 | 真机堆栈 |
 * |---|---|---|
 * | `notifier` | `lateinit` + **顺序错误**：onCreate 第 62 行先调 acquireWakeLock()，第 80 行才赋值 | `acquireWakeLock(ConnectionService.kt:668) ← onCreate(:62)` |
 * | `assetDeployer` | `lateinit` + **多线程竞态**：deployAssets 的 IO 协程抢在主线程赋值前访问 | `ConnectionService$deployAssets$1.invokeSuspend(:163) on DefaultDispatcher-worker-1` |
 *
 * 两者的收场相同：服务一创建即抛 `UninitializedPropertyAccessException`，进程死亡，
 * AMS 把重启排到 30 分钟后 —— 用户看到的是"装完 App 毫无反应"。
 *
 * 修复方式统一为 `by lazy`：构造时机绑定首次访问，顺序与线程都不再是隐式契约。
 * 本测试锁住三点，防止回退：
 *  1. 上述两个字段不得改回 `lateinit`；
 *  2. 它们必须由 `by lazy` 构造；
 *  3. `acquireWakeLock()` 不得借"修顺序"之名被删（投屏期间屏幕可关，CPU 必须持有）。
 *
 * 刻意做成源码文本级断言：不引入 Robolectric，也不依赖任何运行时环境。
 */
class ConnectionServiceInitGuardTest {

    private val source: String by lazy { readSource() }

    /** by lazy 化的字段：访问时机不受 onCreate 语句顺序或线程约束。 */
    private val lazyFields = listOf("notifier", "assetDeployer")

    @Test
    fun guardedFieldsMustNotGoBackToLateinit() {
        for (field in lazyFields) {
            assertFalse(
                "$field 改回 lateinit 会重新引入初始化顺序/竞态崩溃（见类注释），请保持 by lazy。",
                source.contains("lateinit var $field"),
            )
        }
    }

    @Test
    fun guardedFieldsAreDeclaredWithByLazy() {
        for (field in lazyFields) {
            assertTrue(
                "$field 必须由 by lazy 构造（首次访问时初始化），当前源码未匹配到该声明",
                Regex("""private val $field\s*:\s*[\w.]+ by lazy\s*\{""").containsMatchIn(source),
            )
        }
    }

    @Test
    fun wakeLockIsStillAcquired() {
        assertTrue(
            "acquireWakeLock() 调用不得被删除：投屏期间屏幕可关，但 CPU 必须被 PARTIAL_WAKE_LOCK 持有",
            source.contains("acquireWakeLock()"),
        )
    }

    /**
     * START_STICKY 的系统重启投递 `intent = null`，`when (intent?.action)` 必须
     * 有 `null` 分支。真机 2026-10-09：08:42:12 AMS 重启服务进程后不再监听
     * 9637-9639，直到用户手动重开 app —— 根因就是缺这个分支。
     */
    @Test
    fun stickyRestartMustResumeTheServiceOnNullIntent() {
        val body = source
            .substringAfter("override fun onStartCommand")
            .substringBefore("return START_STICKY")
        assertTrue(
            "onStartCommand 必须处理 intent=null（START_STICKY 重启），并恢复前台通知 + 监听循环",
            Regex("""null\s*->""").containsMatchIn(body),
        )
    }

    /** Gradle 测试的工作目录不保证是模块根，向上找到含源文件的目录。 */
    private fun readSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "app-client/src/main/java/com/dilinkauto/client/service/ConnectionService.kt",
            )
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 ConnectionService.kt —— 测试工作目录：${File(".").absolutePath}")
    }
}
