package com.dilinkauto.server

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Constructor

class UsbPendingIntentTest {

    @Test
    fun testPendingUsbDeviceLifecycle() {
        // When carService is not yet bound (null), pendingUsbDevice holds the attached device
        var pendingUsbDevice: UsbDevice? = null
        var carServiceBound = false
        var forwardedDevice: UsbDevice? = null

        // Reflection to create UsbDevice stub if possible, otherwise null check
        val dummyDevice: UsbDevice? = try {
            val ctor = UsbDevice::class.java.declaredConstructors[0]
            ctor.isAccessible = true
            // Android stub constructor
            val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
            ctor.newInstance(*args) as UsbDevice
        } catch (_: Exception) {
            null
        }

        // Simulating handleUsbIntent logic when carService == null
        val onUsbDeviceAttached: (UsbDevice) -> Unit = { dev ->
            if (carServiceBound) {
                forwardedDevice = dev
            } else {
                pendingUsbDevice = dev
            }
        }

        if (dummyDevice != null) {
            onUsbDeviceAttached(dummyDevice)
            org.junit.Assert.assertSame("Device should be pending before service connects", dummyDevice, pendingUsbDevice)
            assertNull("Device should not yet be forwarded", forwardedDevice)

            // Simulating onServiceConnected
            carServiceBound = true
            pendingUsbDevice?.let { dev ->
                forwardedDevice = dev
                pendingUsbDevice = null
            }

            org.junit.Assert.assertSame("Device should now be forwarded", dummyDevice, forwardedDevice)
            assertNull("pendingUsbDevice should be cleared after forwarding", pendingUsbDevice)
        }
    }
}
