package com.dilinkauto.server.service

import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.adb.UsbAdbConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [CarAdbTracker.executeAdb] 的返回值语义与探针回显处理（issue #116）。
 */
class CarAdbTrackerTest {

    private class FakeUsbAdbConnection(
        var isConn: Boolean = true,
        var shellOutput: String? = null,
        var noWaitRc: Int = 0,
    ) : UsbAdbConnection(null) {
        var lastShellCommand: String? = null
        var lastShellNoWaitCommand: String? = null

        override fun isConnected(): Boolean = isConn

        override fun shell(command: String?): String? {
            lastShellCommand = command
            return shellOutput
        }

        override fun shellNoWait(command: String?): Int {
            lastShellNoWaitCommand = command
            return noWaitRc
        }
    }

    private fun createTracker(): CarAdbTracker {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, CarAdbTracker::class.java) as CarAdbTracker
    }

    @Test
    fun executeAdb_probeExitCodeCommand_alive_returnsTrue() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = "Y\n")
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)
        assertTrue("探针回显存活时必须返回 true", result)
        assertEquals("probeExitCodeCommand 必须转换为带回显的 probeCommand", VdDeploy.probeCommand, fakeUsb.lastShellCommand)
    }

    @Test
    fun executeAdb_probeExitCodeCommand_gone_returnsFalse() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = "N\n")
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)
        assertFalse("探针回显消亡时必须返回 false（以便 vdAwaitExit 放行而非卡死 15 秒）", result)
        assertEquals(VdDeploy.probeCommand, fakeUsb.lastShellCommand)
    }

    @Test
    fun executeAdb_probeCommand_alive_returnsTrue() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = "Y\n")
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb(VdDeploy.probeCommand, noWait = false)
        assertTrue(result)
        assertEquals(VdDeploy.probeCommand, fakeUsb.lastShellCommand)
    }

    @Test
    fun executeAdb_probeCommand_gone_returnsFalse() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = "N\n")
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb(VdDeploy.probeCommand, noWait = false)
        assertFalse(result)
    }

    @Test
    fun executeAdb_shellFailure_returnsFalse() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = null)
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)
        assertFalse("底层 shell 执行失败（返回 null）时必须返回 false", result)
    }

    @Test
    fun executeAdb_normalSyncCommand_success_returnsTrue() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = "")
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb("touch /sdcard/DiLinkAuto/stop-request", noWait = false)
        assertTrue("常规同步命令成功执行应返回 true", result)
        assertEquals("touch /sdcard/DiLinkAuto/stop-request", fakeUsb.lastShellCommand)
    }

    @Test
    fun executeAdb_normalSyncCommand_failure_returnsFalse() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, shellOutput = null)
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb("touch /sdcard/DiLinkAuto/stop-request", noWait = false)
        assertFalse("常规同步命令底层失败应返回 false", result)
    }

    @Test
    fun executeAdb_noWait_success_returnsTrue() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, noWaitRc = 1)
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb("app_process ...", noWait = true)
        assertTrue(result)
        assertEquals("app_process ...", fakeUsb.lastShellNoWaitCommand)
    }

    @Test
    fun executeAdb_noWait_failure_returnsFalse() {
        val tracker = createTracker()
        val fakeUsb = FakeUsbAdbConnection(isConn = true, noWaitRc = -1)
        tracker.usbAdb = fakeUsb

        val result = tracker.executeAdb("app_process ...", noWait = true)
        assertFalse(result)
    }

    @Test
    fun executeAdb_notConnected_returnsFalse() {
        val tracker = createTracker()
        assertFalse(tracker.executeAdb("echo hi", noWait = false))
    }
}
