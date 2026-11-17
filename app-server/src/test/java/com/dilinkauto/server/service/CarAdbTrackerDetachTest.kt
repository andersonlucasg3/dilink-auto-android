package com.dilinkauto.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.dilinkauto.protocol.adb.UsbAdbConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 校验 CarAdbTracker ACTION_USB_DEVICE_DETACHED 对拔出设备的身份校验（issue #128）。
 */
class CarAdbTrackerDetachTest {

    private class FakeUsbAdbConnection : UsbAdbConnection(null) {
        var closed = false
        override fun isConnected(): Boolean = !closed
        override fun close() {
            closed = true
        }
    }

    private class FakeDetachIntent(
        private val actionStr: String,
        private val device: UsbDevice?
    ) : Intent(actionStr) {
        override fun getAction(): String = actionStr
        @Suppress("UNCHECKED_CAST")
        override fun <T : android.os.Parcelable?> getParcelableExtra(name: String?): T? {
            if (name == UsbManager.EXTRA_DEVICE) {
                return device as? T
            }
            return null
        }
    }

    private fun <T> allocateInstance(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }

    private fun createTracker(
        context: Context,
        onUsbDetachedMidSession: () -> Unit = {}
    ): CarAdbTracker {
        val carPrefs = allocateInstance(CarPrefs::class.java)
        return CarAdbTracker(
            context = context,
            carPrefs = carPrefs,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            log = { _, _ -> },
            setStatusMessage = { _, _ -> },
            getPhoneHost = { null },
            isHandshakeDone = { false },
            isVdServerStarted = { false },
            deployVdServer = {},
            deployVdServerDirect = {},
            onAdbReady = {},
            onUsbDetachedMidSession = onUsbDetachedMidSession,
            // 本用例只测 detach 身份校验，与自动重连闸门无关（issue #201 起该参数必填）。
            autoReconnectAllowed = { true },
        )
    }

    private fun allocateUsbDevice(deviceId: Int, deviceName: String): UsbDevice {
        val dev = allocateInstance(UsbDevice::class.java)

        // 尝试设置 mDeviceId 或 mName
        var cls: Class<*>? = UsbDevice::class.java
        while (cls != null) {
            try {
                val fId = cls.getDeclaredField("mDeviceId").apply { isAccessible = true }
                fId.set(dev, deviceId)
            } catch (_: Exception) {}
            try {
                val fName = cls.getDeclaredField("mName").apply { isAccessible = true }
                fName.set(dev, deviceName)
            } catch (_: Exception) {}
            cls = cls.superclass
        }
        return dev
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

    @Test
    fun testNonAdbUsbDeviceDetachedDoesNotInterruptSession() {
        val dummyContext = android.content.ContextWrapper(null)
        val detachedMidSessionCalled = AtomicBoolean(false)
        val tracker = createTracker(dummyContext, onUsbDetachedMidSession = { detachedMidSessionCalled.set(true) })
        val fakeUsb = FakeUsbAdbConnection()

        val adbPhoneDevice = allocateUsbDevice(1001, "/dev/bus/usb/001/002")
        val flashDriveDevice = allocateUsbDevice(2002, "/dev/bus/usb/001/003")

        tracker.usbAdb = fakeUsb
        tracker.usbReady = true
        tracker.currentUsbDevice = adbPhoneDevice
        tracker.isSameUsbDevice = { a, b -> a === b }

        // 模拟拔出非 ADB 设备 (flashDriveDevice)
        val detachIntent = FakeDetachIntent(UsbManager.ACTION_USB_DEVICE_DETACHED, flashDriveDevice)

        tracker.usbReceiver.onReceive(dummyContext, detachIntent)

        // 验证：
        // 1. onUsbDetachedMidSession 未被触发
        assertFalse("拔出非 ADB 外设时不应触发 onUsbDetachedMidSession", detachedMidSessionCalled.get())
        // 2. 当前 ADB 连接依然存活
        assertFalse("fakeUsb 不应被 close", fakeUsb.closed)
        assertNotNull("usbAdb 不应被置空", tracker.usbAdb)
        assertTrue("usbReady 应维持 true", tracker.usbReady)
        assertTrue("currentUsbDevice 应维持原 ADB 设备", adbPhoneDevice === tracker.currentUsbDevice)

        // 模拟拔出当前 ADB 手机设备 (adbPhoneDevice)
        val adbDetachIntent = FakeDetachIntent(UsbManager.ACTION_USB_DEVICE_DETACHED, adbPhoneDevice)

        tracker.usbReceiver.onReceive(dummyContext, adbDetachIntent)

        // 验证：
        // 1. onUsbDetachedMidSession 被触发
        assertTrue("拔出当前 ADB 设备时必须触发 onUsbDetachedMidSession", detachedMidSessionCalled.get())
        // 2. fakeUsb 被 close 并清理
        assertTrue("fakeUsb 必须被 close", fakeUsb.closed)
        assertNull("usbAdb 必须被置空", tracker.usbAdb)
        assertFalse("usbReady 必须置为 false", tracker.usbReady)
        assertNull("currentUsbDevice 必须置空", tracker.currentUsbDevice)
    }
}
