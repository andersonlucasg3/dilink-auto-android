package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayCreatorTest {

    @Test
    fun testInitialDisplayIdIsNegative() {
        val creator = VirtualDisplayCreator(
            displayWidth = 1920,
            displayHeight = 1080,
            dpi = 160,
            execShell = {},
            execShellOutput = { null },
            log = {},
            err = {}
        )
        assertEquals(-1, creator.displayId)
    }
}
