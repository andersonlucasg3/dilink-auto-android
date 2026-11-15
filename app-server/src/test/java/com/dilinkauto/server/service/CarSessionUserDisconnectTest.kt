package com.dilinkauto.server.service

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.dilinkauto.protocol.PhoneStatus
import com.dilinkauto.server.AppIconCache
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.decoder.AudioPlayer
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * issue #183 的回归钉子：**用户主动断开**必须把会话真正拆掉并回到 IDLE。
 *
 * 缺陷形态：`disconnectFromPhone()` 原先只发一次 `DISCONNECT` 再
 * `disconnectAllConnections()`，状态迁移全靠 `Connection.performTeardown()` 的
 * listener 副作用 —— 若该连接此前已 disconnect 过（CAS 失败）就不再回调，于是
 * `_state` 停在 STREAMING、三个 Job 仍 active，`userDisconnected` 又抑制了自动
 * 重连，车机端表现为「不杀后台就再也连不回去」。
 *
 * 沿用 [CarSessionCoordinatorConnectTest] 的 `Unsafe.allocateInstance` + 反射注入
 * 范式：`CarSessionCoordinator` 的依赖里有 Android Context / SharedPreferences，
 * 纯 JVM 下只能这样构造。真机可见行为仍由回签复核。
 */
class CarSessionUserDisconnectTest {

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

    @Test
    fun `user disconnect tears the session down and returns to IDLE`() {
        // reset() 会调 ServerApp.iconCache.clear()，lateinit 未初始化会抛；Kotlin 把
        // companion 的 lateinit var 编译成 ServerApp 上的 private static 字段，只能反射写入。
        ServerApp::class.java.getDeclaredField("iconCache").apply {
            isAccessible = true
            set(null, AppIconCache(File(System.getProperty("java.io.tmpdir"), "dilink-icon-test-183")))
        }

        val carPrefs = allocateInstance(CarPrefs::class.java)
        setField(carPrefs, "prefs", InMemoryPrefs())

        val adbTracker = allocateInstance(CarAdbTracker::class.java)
        val coordinator = allocateInstance(CarSessionCoordinator::class.java)
        val stateFlow = MutableStateFlow(CarConnectionService.State.STREAMING)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        setField(coordinator, "scope", testScope)
        setField(coordinator, "_state", stateFlow)
        setField(coordinator, "_phoneName", MutableStateFlow("Meizu 20"))
        setField(coordinator, "_videoReady", MutableStateFlow(true))
        setField(coordinator, "_statusMessage", MutableStateFlow(""))
        setField(coordinator, "logWriter", CarLogWriter("test-183") { null })
        setField(coordinator, "videoDecoder", VideoDecoder())
        setField(coordinator, "audioPlayer", AudioPlayer())
        setField(coordinator, "audioExecutor", newAudioExecutor())
        setField(coordinator, "appListReceiver", CarAppListReceiver(ContextWrapper(null)))
        setField(coordinator, "adbTrackerProvider", { adbTracker })
        setField(
            coordinator,
            "touchSender",
            CarTouchSender({ null }, { CarConnectionService.State.IDLE }, { _, _ -> }),
        )
        setField(coordinator, "onPhoneStatus", { _: PhoneStatus? -> })
        setField(coordinator, "updateNotification", { _: Int -> })
        setField(coordinator, "carPrefs", carPrefs)
        setField(coordinator, "teardownGuard", AtomicBoolean(false))
        setField(coordinator, "reHandshakeInFlight", false)
        setField(
            coordinator,
            "viewportGate",
            ViewportRehandshakeGate(
                debounceMs = 700,
                schedule = { _, _ -> ViewportRehandshakeGate.Cancelable { } },
                request = { },
                isInFlight = { false },
            ),
        )

        // 模拟一个真实会话持有的三处 Job。
        val connectionScope = Job()
        val connectJob = Job()
        val discoveryJob = Job()
        setField(coordinator, "connectionScope", connectionScope)
        setField(coordinator, "connectJob", connectJob)
        setField(coordinator, "discoveryJob", discoveryJob)

        coordinator.disconnectFromPhone()

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline &&
            stateFlow.value != CarConnectionService.State.IDLE
        ) {
            Thread.sleep(20)
        }

        assertEquals(
            "用户主动断开后必须回到 IDLE，否则 UI 停在镜像页且没有重连入口（issue #183）",
            CarConnectionService.State.IDLE,
            stateFlow.value,
        )
        assertTrue("connectionScope 必须被取消", connectionScope.isCancelled)
        assertTrue("connectJob 必须被取消", connectJob.isCancelled)
        assertTrue("discoveryJob 必须被取消", discoveryJob.isCancelled)
        assertTrue("userDisconnected 必须落盘为 true，抑制自动重连", carPrefs.userDisconnected)
    }
}
