package com.dilinkauto.client.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [CarIpLocator]. The locator is a plain object (no Service
 * dependency) so its non-network helpers are testable directly.
 *
 * Reachability itself is stubbed through [CarIpLocator.portProbeOverride] (the
 * JVM test stub makes the Android-aware paths return defaults, and a real
 * probe would assert something about the test machine's LAN, not about this
 * code). What is asserted: a closed local port times out within the deadline
 * rather than hanging, the strategy chain returns null without throwing when
 * nothing is reachable, the published subnet snapshot drives the sweep, and
 * concurrent scans are single-flight (A-M8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarIpLocatorTest {

    @Test
    fun probePortSync_returnsFalseOnClosedPort() {
        // Port 1 is reserved and never open on a test host — probe must time out
        // to false within the 500ms deadline rather than hanging.
        val start = System.currentTimeMillis()
        val result = CarIpLocator.probePortSync("127.0.0.1", 1)
        val elapsed = System.currentTimeMillis() - start
        assertFalse("Probe to closed port 1 must fail", result)
        // 500ms timeout + small slack; must not hang
        assertTrue("Probe must respect the 500ms deadline (took ${elapsed}ms)",
            elapsed < 1500)
    }

    @Test
    fun findCarAdb_returnsNullWhenNoControlConnectionAndNoInterfaces() = runTest {
        // A null remote IP, no WiFi manager, and a probe that reports every host
        // as closed. The probe is stubbed because findCarAdb sweeps real
        // subnets — without the stub this asserts "no machine on this LAN is
        // listening on 5555", which is a property of the test host, not of
        // CarIpLocator, and fails on any developer machine with a real car or
        // another ADB device on the same network.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.portProbeOverride = { _, _ -> false }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = null)
            assertNull("findCarAdb must return null when no car is reachable", result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }

    @Test
    fun findCarAdb_prefersTheControlConnectionRemoteIpWhenItsPortIsOpen() = runTest {
        // With a reachable control connection the first strategy must win,
        // before any subnet scanning happens.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.portProbeOverride = { ip, port -> ip == "10.0.0.7" && port == 5555 }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = "10.0.0.7")
            assertEquals("10.0.0.7", result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }

    @Test
    fun findCarAdb_ignoresAClosedControlConnectionAndKeepsSearching() = runTest {
        // A control connection whose ADB port is closed must not end the search —
        // the car may be reachable on another strategy.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            // Nothing is reachable, so this asserts the closed control IP is
            // discarded and the scan completes with null rather than short-circuit.
            CarIpLocator.portProbeOverride = { _, _ -> false }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = "10.0.0.7")
            assertNull(result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }

    @Test
    fun concurrentFindCarAdbCallsAreSerializedIntoOneScan() = runTest {
        // A-M8: two concurrent installs used to run the whole strategy chain
        // twice, two /24 sweeps at the same time. The fix serializes the
        // whole scan behind a mutex; the observable contract is that the
        // second caller cannot make ANY progress — not even its first probe
        // — while the first is still scanning.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            // One published prefix keeps the sweep small and deterministic.
            CarIpLocator.setLocalSubnetSnapshot(listOf("10.99.99.5"))

            val aMarker = "10.0.0.1"
            val bMarker = "10.0.0.2"
            val markerProbes = CopyOnWriteArrayList<String>()
            val aScanning = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            CarIpLocator.portProbeOverride = { ip, _ ->
                when (ip) {
                    // Strategy 1 (the "control connection" probe) doubles as
                    // each call's entry marker: park call A here, mid-scan.
                    aMarker -> {
                        markerProbes.add(ip)
                        aScanning.countDown()
                        // Bounded, so a failed assertion above can never park
                        // this call (and the test) forever.
                        releaseA.await(10, TimeUnit.SECONDS)
                        false
                    }
                    bMarker -> {
                        markerProbes.add(ip)
                        false
                    }
                    else -> false
                }
            }

            val a = async(Dispatchers.Default) { CarIpLocator.findCarAdb(aMarker) }
            assertTrue("call A must reach its first probe", aScanning.await(5, TimeUnit.SECONDS))

            val b = async(Dispatchers.Default) { CarIpLocator.findCarAdb(bMarker) }
            // Real-time window (not the virtual clock): if the scan were not
            // single-flight, B would probe its marker within milliseconds.
            // While it is, B is parked on the mutex and probes nothing.
            TimeUnit.MILLISECONDS.sleep(500)
            assertTrue(markerProbes.contains(aMarker))
            assertFalse(
                "the second scan must not probe anything until the first finishes",
                markerProbes.contains(bMarker)
            )

            releaseA.countDown()
            val results = awaitAll(a, b)
            assertNull(results[0])
            assertNull(results[1])
            assertTrue("B must run only after A finished", markerProbes.contains(bMarker))
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
            CarIpLocator.clearLocalSubnetSnapshot()
        }
    }

    @Test
    fun findCarAdb_usesThePublishedSubnetSnapshotForTheSweep() = runTest {
        // A-M8: the network-change owner publishes the subnet list once, and
        // the /24 sweep must run against that snapshot rather than a fresh
        // per-call interface walk. Prove it by publishing a prefix no test
        // host plausibly owns and answering only for one IP inside it.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.setLocalSubnetSnapshot(listOf("10.99.99.5"))
            CarIpLocator.portProbeOverride = { ip, port -> ip == "10.99.99.7" && port == 5555 }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = null)
            assertEquals("the sweep must follow the published snapshot", "10.99.99.7", result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
            CarIpLocator.clearLocalSubnetSnapshot()
        }
    }

    @Test
    fun findCarAdb_fallsBackToALiveWalkWithoutASnapshot() = runTest {
        // The snapshot is optional (published by the lead's call site): with
        // none published the locator must still enumerate interfaces itself.
        // Prove it by answering only for an IP derived from this host's own
        // live address — only a real per-call walk produces that prefix.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.clearLocalSubnetSnapshot()
            val hostIp = NetUtil.localIpv4Addresses().firstOrNull() ?: return@runTest
            val prefix = hostIp.substringBeforeLast(".")
            val ownLast = hostIp.substringAfterLast(".").toInt()
            // A candidate the sweep will actually probe: not an own IP, in
            // 1..254, and arithmetic that can never circle back to ownLast.
            val candidateLast = if (ownLast >= 128) ownLast - 127 else ownLast + 127
            val candidate = "$prefix.$candidateLast"
            CarIpLocator.portProbeOverride = { ip, port -> ip == candidate && port == 5555 }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = null)
            assertEquals("the sweep must follow the live interface walk", candidate, result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
            CarIpLocator.clearLocalSubnetSnapshot()
        }
    }
}


