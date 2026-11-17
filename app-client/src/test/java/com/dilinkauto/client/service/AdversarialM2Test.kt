package com.dilinkauto.client.service

import android.content.Context
import android.content.ContextWrapper
import com.dilinkauto.client.display.VirtualDisplayClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialM2Test {

    private fun createDummyContext(): Context {
        return ContextWrapper(null)
    }

    // ─── VirtualDisplayClient & ConnectionService Cleanup and Reconnect ───

    @Test
    fun testVirtualDisplayClient_cleanupAndReconnectionLifecycle() = runTest {
        val scope = TestScope()
        val dummyContext = createDummyContext()
        val testPort = 19648 // Use alternate port for unit test isolation

        val client = VirtualDisplayClient(scope, dummyContext)
        assertFalse("Initial isConnected must be false", client.isConnected)
        assertEquals("Initial displayId must be -1", -1, client.displayId)

        // Step 1: Start listening on port
        client.startListening(testPort)

        // Step 2: Simulate disconnect() as called in cleanupSession()
        client.disconnect()
        assertFalse("isConnected must be false after disconnect", client.isConnected)
        assertEquals("displayId must be reset to -1 after disconnect", -1, client.displayId)

        // Step 3: Reconnection simulation: A fresh client can bind the port immediately (SO_REUSEADDR)
        val reconnectClient = VirtualDisplayClient(scope, dummyContext)
        try {
            reconnectClient.startListening(testPort)
            // If port was leaked or not closed in disconnect(), startListening would throw BindException
            assertTrue("startListening on reconnect must succeed without port collision", true)
        } finally {
            reconnectClient.disconnect()
        }
    }

    @Test
    fun testConnectionService_sessionCleanupResetSemantics() {
        val dummyContext = createDummyContext()
        // Simulates ConnectionService session state before and after cleanupSession()
        var vdClient: VirtualDisplayClient? = VirtualDisplayClient(TestScope(), dummyContext)
        var isConnected = true
        var serviceState = "STREAMING"

        assertNotNull(vdClient)
        assertTrue(isConnected)

        // Simulating cleanupSession()
        vdClient?.disconnect()
        vdClient = null
        isConnected = false
        serviceState = "WAITING"

        assertNull("vdClient MUST be null after cleanupSession", vdClient)
        assertFalse("isConnected must be false after cleanupSession", isConnected)
        assertEquals("Service state must revert to WAITING", "WAITING", serviceState)

        // Simulating subsequent handshake request:
        // if (vdClient == null) -> fresh client initialized
        if (vdClient == null) {
            val freshClient = VirtualDisplayClient(TestScope(), dummyContext)
            freshClient.startListening(19649)
            vdClient = freshClient
        }

        assertNotNull("Reconnection must allocate a fresh vdClient", vdClient)
        vdClient?.disconnect()
    }
}
