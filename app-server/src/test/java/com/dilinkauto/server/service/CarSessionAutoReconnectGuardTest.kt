package com.dilinkauto.server.service

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #201 的行为级 + 源码级双钉子。
 *
 * 缺陷形态：`userDisconnected` 只在 `handleDisconnect` 的重试回环生效；`onAdbReady`、
 * 服务 `ACTION_START`、USB attach、TCP ADB 重拨都会绕到 `startConnection()`，而它自己
 * 无条件把标志清回 false —— USB 重新枚举（3~13s 内很常见）就把会话拉起来了。
 *
 *  - 行为级：直接驱动闸门，证明标志 / 冷却确实能挡住自动入口、且不误伤用户入口；
 *  - 源码级：证明**每个**入口都走了同一判定（删掉任何一个判定都会红）。
 *
 * 沿用 [CarSessionUserDisconnectTest] 的 `Unsafe.allocateInstance` 范式：协调器依赖
 * Android Context / SharedPreferences，纯 JVM 下只能这样构造。真机可见行为由回签复核。
 */
class CarSessionAutoReconnectGuardTest {

    /** 内存版 SharedPreferences —— `CarPrefs` 只用到 getBoolean / edit().putBoolean()。 */
    private class InMemoryPrefs : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (store[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = also { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = also { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = also { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = also { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = also { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = also { removals.add(key!!) }
            override fun clear(): SharedPreferences.Editor = also { removals.addAll(store.keys) }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() = flush()

            private fun flush() {
                removals.forEach { store.remove(it) }
                store.putAll(pending)
            }
        }
    }

    private fun <T> allocateInstance(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz) as T
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun getField(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun newCarPrefs(userDisconnected: Boolean): CarPrefs {
        val prefs = allocateInstance(CarPrefs::class.java)
        setField(prefs, "prefs", InMemoryPrefs())
        prefs.userDisconnected = userDisconnected
        return prefs
    }

    private fun newCoordinator(carPrefs: CarPrefs): CarSessionCoordinator {
        val coordinator = allocateInstance(CarSessionCoordinator::class.java)
        setField(coordinator, "scope", CoroutineScope(SupervisorJob() + Dispatchers.Default))
        setField(coordinator, "carPrefs", carPrefs)
        setField(coordinator, "logWriter", CarLogWriter("test-201") { null })
        setField(coordinator, "_state", MutableStateFlow(CarConnectionService.State.IDLE))
        return coordinator
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(coordinator: CarSessionCoordinator): MutableStateFlow<CarConnectionService.State> =
        getField(coordinator, "_state") as MutableStateFlow<CarConnectionService.State>

    // ─── 行为级 ───

    @Test
    fun `auto entry is a no-op after the user disconnected`() {
        val carPrefs = newCarPrefs(userDisconnected = true)
        val coordinator = newCoordinator(carPrefs)

        coordinator.startConnection(ConnectTrigger.AUTO_ADB_READY)

        assertEquals(
            "用户断开后自动入口不得推进状态机（issue #201）",
            CarConnectionService.State.IDLE,
            stateOf(coordinator).value,
        )
        assertTrue("自动入口不得清除 userDisconnected", carPrefs.userDisconnected)
        assertNull("不得为自动入口创建连接 Job", getField(coordinator, "connectionScope"))
    }

    @Test
    fun `cooldown blocks auto entry even when the persisted flag was cleared`() {
        val carPrefs = newCarPrefs(userDisconnected = false)
        val coordinator = newCoordinator(carPrefs)
        // JVM 下 SystemClock.elapsedRealtime() 恒为 0（unitTests.isReturnDefaultValues），
        // 把起点设在 1 即等价于「刚刚断开、仍在冷却窗口内」。
        setField(coordinator, "lastUserDisconnectAtMs", 1L)

        assertFalse(
            "冷却期内自动入口必须被挡住（issue #201 候选 B）",
            coordinator.allowReconnect(ConnectTrigger.AUTO_ADB_READY),
        )
        assertEquals(
            CarConnectionService.State.IDLE,
            stateOf(coordinator).value,
        )
    }

    @Test
    fun `auto entry passes with a clean slate`() {
        val coordinator = newCoordinator(newCarPrefs(userDisconnected = false))
        assertTrue("正常状态下 ADB 就绪必须放行", coordinator.allowReconnect(ConnectTrigger.AUTO_ADB_READY))
    }

    @Test
    fun `user reconnect passes the gate and clears the flag and cooldown`() {
        val carPrefs = newCarPrefs(userDisconnected = true)
        val coordinator = newCoordinator(carPrefs)
        setField(coordinator, "lastUserDisconnectAtMs", 1L)

        assertTrue(
            "用户点重连必须放行，否则按钮看起来坏了（issue #183 回归）",
            coordinator.allowReconnect(ConnectTrigger.USER_RECONNECT),
        )
        assertFalse("用户点重连必须清除 userDisconnected", carPrefs.userDisconnected)
        assertEquals(
            "用户点重连必须同时解除冷却，否则紧接着的自动重试会被误挡",
            0L,
            getField(coordinator, "lastUserDisconnectAtMs") as Long,
        )
    }

    // ─── 源码级钉子 ───

    private val coordinatorSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt")
    private val serviceSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt")
    private val trackerSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarAdbTracker.kt")

    @Test
    fun `every automatic entry goes through the shared gate`() {
        assertTrue(
            "startConnection 必须显式接收触发来源，否则调用方无法被追溯",
            coordinatorSrc.contains("fun startConnection(trigger: ConnectTrigger)"),
        )
        assertTrue(
            "startConnection 必须在改动任何状态前先过闸门",
            coordinatorSrc.contains("if (!allowReconnect(trigger)) return"),
        )
        assertTrue(
            "WiFi 探测循环（网关/mDNS）也必须过闸门",
            coordinatorSrc.contains("if (!allowReconnect(ConnectTrigger.AUTO_DISCOVERY)) return"),
        )
        assertTrue(
            "ADB 就绪入口必须带 AUTO_ADB_READY",
            serviceSrc.contains("sessionCoordinator.startConnection(ConnectTrigger.AUTO_ADB_READY)"),
        )
        assertTrue(
            "服务 ACTION_START 入口必须带 AUTO_SERVICE_START",
            serviceSrc.contains("sessionCoordinator.startConnection(ConnectTrigger.AUTO_SERVICE_START)"),
        )
        assertTrue(
            "网络恢复回调必须过闸门",
            serviceSrc.contains("allowReconnect(ConnectTrigger.AUTO_NETWORK_AVAILABLE)"),
        )
        assertTrue(
            "USB/TCP ADB 的探测入口必须接到同一个闸门",
            serviceSrc.contains("autoReconnectAllowed = { trigger -> sessionCoordinator.allowReconnect(trigger) }"),
        )
        assertEquals(
            "CarAdbTracker 的每个 ADB 自动入口都必须过闸门（扫描 / TCP track / TCP 重拨 / attach / 权限回调）",
            5,
            Regex("""autoReconnectAllowed\(ConnectTrigger\.AUTO_ADB_READY\)""").findAll(trackerSrc).count(),
        )
    }

    @Test
    fun `no caller can start a connection without naming the trigger`() {
        val bareCall = Regex("""startConnection\(\s*\)""")
        assertFalse(
            "startConnection() 无参调用必须绝迹（issue #201：调用方必须自报来源）",
            bareCall.containsMatchIn(coordinatorSrc) || bareCall.containsMatchIn(serviceSrc),
        )
    }

    @Test
    fun `usb attach no longer clears the user disconnect flag`() {
        assertFalse(
            "CarAdbTracker 不得再改写 userDisconnected：USB 重新枚举 != 用户重新连接",
            trackerSrc.contains("userDisconnected = false"),
        )
        val occurrences = Regex("""userDisconnected = false""").findAll(coordinatorSrc).count()
        assertEquals(
            "协调器里只允许闸门一处清除标志，其余入口一律不得裸写",
            1,
            occurrences,
        )
        val gateBody = coordinatorSrc
            .substringAfter("fun allowReconnect(trigger: ConnectTrigger): Boolean")
            .substringBefore("fun startConnection(trigger: ConnectTrigger)")
        assertTrue(
            "唯一的清除点必须在 allowReconnect 的闸门内（且只对用户触发生效）",
            gateBody.contains("carPrefs.userDisconnected = false"),
        )
    }

    @Test
    fun `user disconnect starts the cooldown clock`() {
        val body = coordinatorSrc
            .substringAfter("fun disconnectFromPhone()")
            .substringBefore("fun reconnect()")
        assertTrue(
            "用户断开必须记录冷却起点，否则进程内没有任何兜底",
            body.contains("lastUserDisconnectAtMs = SystemClock.elapsedRealtime()"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceFileOf(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }

    private fun sourceOf(relativePath: String): String = sourceFileOf(relativePath).readText()
}
