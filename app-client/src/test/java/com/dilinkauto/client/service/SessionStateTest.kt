package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    @Test
    fun `isRunning correctly distinguishes idle from active states`() {
        assertFalse(SessionState.IDLE.isRunning)
        assertTrue(SessionState.WAITING.isRunning)
        assertTrue(SessionState.CONNECTED.isRunning)
        assertTrue(SessionState.STREAMING.isRunning)
    }

    @Test
    fun `connection service typealias points to session state`() {
        val state: State = SessionState.STREAMING
        assertEquals(SessionState.STREAMING, state)
        assertTrue(state.isRunning)
    }
}
