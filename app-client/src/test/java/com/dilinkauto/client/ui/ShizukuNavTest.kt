package com.dilinkauto.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuNavTest {

    @Test
    fun `shizuku package and web url constants are preserved`() {
        assertEquals("moe.shizuku.privileged.api", ShizukuNav.SHIZUKU_PACKAGE)
        assertEquals("https://github.com/RikkaApps/Shizuku/releases", ShizukuNav.SHIZUKU_WEB_URL)
    }
}
