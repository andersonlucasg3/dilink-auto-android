package com.dilinkauto.server.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 验证初次连接手机（WiFi 探测）失败时，不连坐取消父作用域 connectionScope 或调用 handleDisconnect（issue #127）。
 */
class CarSessionCoordinatorConnectTest {

    private fun allocateCoordinator(): CarSessionCoordinator {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, CarSessionCoordinator::class.java) as CarSessionCoordinator
    }

    private fun allocateAdbTracker(): CarAdbTracker {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, CarAdbTracker::class.java) as CarAdbTracker
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
    fun testConnectToPhoneFailureDoesNotCancelConnectionScopeOrEnterBackoff() = runBlocking {
        val coordinator = allocateCoordinator()
        val adbTracker = allocateAdbTracker()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val stateFlow = MutableStateFlow(CarConnectionService.State.CONNECTING)
        setField(coordinator, "scope", testScope)
        setField(coordinator, "_state", stateFlow)
        setField(coordinator, "adbTrackerProvider", { adbTracker })
        setField(coordinator, "audioExecutor", java.util.concurrent.Executors.newSingleThreadExecutor())
        setField(coordinator, "handshakeDone", false)
        setField(coordinator, "consecutiveFailures", 0)
        setField(coordinator, "teardownGuard", AtomicBoolean(false))

        val parentJob = Job()
        setField(coordinator, "connectionScope", parentJob)

        // 尝试连接一个不可达的主机/端口，connectToPhone 将抛出 IOException (Connection refused 等)
        coordinator.connectToPhone("127.0.0.1", 59999)

        // 等待 connectJob 完成
        var attempts = 0
        while (attempts < 50) {
            val job = coordinator.javaClass.getDeclaredField("connectJob").apply { isAccessible = true }.get(coordinator) as? Job
            if (job == null || !job.isActive) {
                break
            }
            Thread.sleep(50)
            attempts++
        }

        // 验证：
        // 1. connectionScope (parentJob) 仍然处于 active 状态，未被 cancel
        assertTrue("connectionScope 必须保持活跃，不能被单次 WiFi 建连异常连坐取消", parentJob.isActive)
        assertFalse("connectionScope 不应被标记为 cancelled", parentJob.isCancelled)

        // 2. 状态保持 CONNECTING，不跌入 IDLE
        assertEquals("状态应维持 CONNECTING", CarConnectionService.State.CONNECTING, stateFlow.value)

        // 3. 失败次数 consecutiveFailures 不递增
        val failures = coordinator.javaClass.getDeclaredField("consecutiveFailures").apply { isAccessible = true }.getInt(coordinator)
        assertEquals("consecutiveFailures 不应递增", 0, failures)

        parentJob.cancel()
    }
}
