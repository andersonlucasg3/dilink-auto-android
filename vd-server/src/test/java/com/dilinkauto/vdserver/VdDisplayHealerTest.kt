package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class VdDisplayHealerTest {

    private val visibleDump = """
Display #7 (activities from top to bottom):
  * Task{1a2b3c #34033 type=standard A=com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{b68 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34033}
"""

    private val invisibleDump = """
Display #7 (activities from top to bottom):
  * Task{1a2b3c #34033 type=standard A=com.autonavi.minimap U=0 visible=true visibleRequested=false mode=fullscreen translucent=true sz=1}
    * ActivityRecord{b68 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34033}
"""

    @Test
    fun healDisplaySkipsSurgeryWhenAlreadyVisible() {
        val execOuts = CopyOnWriteArrayList<String>()
        val layerRepairs = AtomicInteger(0)
        val healer = VdDisplayHealer(
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity activities") -> visibleDump
                    else -> null
                }
            },
            displayId = { 7 },
            repairLayerTree = { layerRepairs.incrementAndGet() },
            healVerifyMs = 10L
        )

        healer.healDisplay()
        waitFor { layerRepairs.get() > 0 }
        assertTrue("已可见时不执行 am start 或 move-stack", execOuts.none {
            it.startsWith("am start") || it.startsWith("am display move-stack")
        })
        assertEquals(1, layerRepairs.get())
    }

    @Test
    fun healDisplayReFrontsInvisibleTopTask() {
        val execOuts = CopyOnWriteArrayList<String>()
        val layerRepairs = AtomicInteger(0)
        val healer = VdDisplayHealer(
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity activities") -> invisibleDump
                    cmd.startsWith("am start") -> "Starting: Intent { ... }"
                    else -> null
                }
            },
            displayId = { 7 },
            repairLayerTree = { layerRepairs.incrementAndGet() },
            healVerifyMs = 10L
        )

        healer.healDisplay()
        waitFor { execOuts.any { it.startsWith("am start --display 7 --task") } }
        assertTrue(
            execOuts.contains(
                "am start --display 7 --task 34033 -n 'com.autonavi.minimap/com.autonavi.map.activity.SplashActivity'"
            )
        )
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }
}
