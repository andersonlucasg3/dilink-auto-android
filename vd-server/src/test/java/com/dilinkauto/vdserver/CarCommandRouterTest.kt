package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CarCommandRouterTest {

    private fun control(type: Byte, payload: ByteArray = ByteArray(0)) =
        FrameCodec.Frame(Channel.CONTROL, type, payload)

    @Test
    fun testCheckStackEmptyDebouncesConcurrentCalls() {
        val dumpsysCount = AtomicInteger(0)
        val inDumpsys = CountDownLatch(1)
        val allowDumpsysFinish = CountDownLatch(1)

        val router = CarCommandRouter(
            exec = {},
            execOut = { cmd ->
                if (cmd.startsWith("dumpsys activity activities")) {
                    dumpsysCount.incrementAndGet()
                    inDumpsys.countDown()
                    allowDumpsysFinish.await(2, TimeUnit.SECONDS)
                    ""
                } else null
            },
            displayId = { 0 },
            onStackEmpty = {},
            setDisplayPower = {},
            requestSyncFrame = {},
            repairLayerTree = {}
        )

        // Dispatch first GO_BACK to trigger StackCheck
        router.dispatch(control(ControlMsg.GO_BACK))
        // Wait until it enters dumpsys
        assertTrue(inDumpsys.await(2, TimeUnit.SECONDS))

        // Dispatch second and third GO_BACK while the first is in-flight
        router.dispatch(control(ControlMsg.GO_BACK))
        router.dispatch(control(ControlMsg.GO_HOME))

        // Allow first to finish
        allowDumpsysFinish.countDown()
        Thread.sleep(500)

        // Only 1 dumpsys should have been performed due to debouncing
        assertTrue("Expected 1 dumpsys execution due to debounce, got ${dumpsysCount.get()}", dumpsysCount.get() == 1)
    }
}
