package com.dilinkauto.client.service

import android.net.wifi.WifiManager
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.Ipv4Validator
import com.dilinkauto.protocol.Ports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Locates the car's ADB-over-WiFi service (port 5555) on the local network.
 *
 * Strategies, in order of latency/cost:
 *  1. The control TCP connection's remote address (free — car is already connected).
 *  2. Subnet enumeration (so later strategies can skip own IPs).
 *  3. ARP table (`/proc/net/arp`).
 *  4. Neighbor cache (`ip neigh`).
 *  5. Parallel /24 scan (slowest — full 1-254 probe per prefix).
 *  6. WiFi gateway.
 *
 * Extracted from [ConnectionService] (Phase K3 / code-audit 5.3) so the scan
 * logic is unit-testable without a `Service` and the connection-lifecycle code
 * stays small.
 */
object CarIpLocator {

    private const val TAG = "CarIpLocator"
    private const val CAR_ADB_PORT = Ports.ADB_PORT

    /**
     * Test seam for the port probe.
     *
     * [findCarAdb] scans every strategy in the caller's real network: local
     * subnets, /proc/net/arp, the neighbour cache, a parallel /24 sweep and the
     * gateway. On a developer machine that means it can legitimately find a
     * *real* host listening on 5555, so a test asserting "nothing reachable" was
     * asserting something about the machine it ran on rather than about this
     * code — it failed on any LAN with an ADB-capable device and passed on an
     * isolated runner.
     *
     * Set to a lambda in tests to make reachability deterministic. Production
     * leaves it null and the real probe runs.
     */
    @Volatile
    var portProbeOverride: ((String, Int) -> Boolean)? = null

    private fun probe(ip: String, port: Int): Boolean =
        portProbeOverride?.invoke(ip, port) ?: probePortBlocking(ip, port, 500)

    /**
     * Serializes [findCarAdb] (audit A-M8): two concurrent car-app installs
     * used to each walk the full strategy chain, running two /24 sweeps at
     * once — 508 sockets across two interfaces on one phone. The install path
     * can genuinely be entered twice before the first finishes, so the scan
     * itself is now single-flight; a second caller waits for the first result
     * instead of duplicating it.
     */
    private val scanMutex = Mutex()

    /** Returns the car's IPv4 address, or null if no ADB endpoint was found. */
    suspend fun findCarAdb(controlConnectionRemoteIp: String?): String? = scanMutex.withLock {
        // 1. Check the control connection's remote address (car is already connected)
        controlConnectionRemoteIp?.let { ip ->
            if (probe(ip, CAR_ADB_PORT)) {
                FileLog.i(TAG, "Found car ADB at $ip (control connection)")
                return@withLock ip
            }
        }

        // 2. Scan ALL local subnets (phone may be on both home WiFi + hotspot).
        // Snapshot-safe (A-M8): prefer the published snapshot so the sweep and
        // the own-IP filter share one immutable view; fall back to a live walk
        // until the network-change owner publishes one.
        val subnetIps = subnetSnapshot ?: getLocalSubnetIps()
        val prefixes = subnetIps.map { it.substringBeforeLast(".") }.distinct()
        FileLog.d(TAG, "Local subnets: $subnetIps (prefixes: $prefixes)")

        // 3. ARP table (may be blocked on Android 14+)
        try {
            for (line in java.io.File("/proc/net/arp").readLines().drop(1)) {
                val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                if (ip == "0.0.0.0") continue
                if (subnetIps.contains(ip)) continue
                if (probe(ip, CAR_ADB_PORT)) {
                    FileLog.i(TAG, "Found car ADB at $ip (ARP)")
                    return@withLock ip
                }
            }
        } catch (e: Exception) {
            FileLog.d(TAG, "ARP not available: ${e.message}")
        }

        // 4. Neighbor cache
        val neighProcess = try {
            Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
        } catch (_: Exception) { null }
        if (neighProcess != null) {
            try {
                val out = neighProcess.inputStream.bufferedReader().readText()
                for (line in out.lines()) {
                    val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                    if (!Ipv4Validator.isLoose(ip)) continue
                    if (subnetIps.contains(ip)) continue
                    if (probe(ip, CAR_ADB_PORT)) {
                        FileLog.i(TAG, "Found car ADB at $ip (neighbor)")
                        return@withLock ip
                    }
                }
            } catch (_: Exception) {} finally {
                // Always reap the process — a leaked `ip` child pins a PID and an
                // FD pair until GC, and on a reconnect loop we'd accumulate dozens.
                neighProcess.inputStream.close()
                neighProcess.errorStream.close()
                neighProcess.outputStream.close()
                neighProcess.destroy()
            }
        }

        // 5. Parallel scan on ALL subnets
        for (prefix in prefixes) {
            FileLog.i(TAG, "Scanning $prefix.0/24 for ADB...")
            val startMs = System.currentTimeMillis()
            val result = probeSubnetConcurrent(prefix, ownIps = subnetIps, maxConcurrent = 32)
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                FileLog.i(TAG, "Found car ADB at $result ($prefix.0/24, ${elapsed}ms)")
                return@withLock result
            }
            FileLog.d(TAG, "$prefix.0/24: no ADB found (${elapsed}ms)")
        }

        // 6. Gateway
        try {
            @Suppress("DEPRECATION")
            val wm = wifiManager ?: return@withLock null
            val ip = com.dilinkauto.protocol.WifiGatewayIp.format(wm.dhcpInfo.gateway)
            if (ip != null && !subnetIps.contains(ip) && probePort(ip, CAR_ADB_PORT)) {
                FileLog.i(TAG, "Found car ADB at $ip (gateway)")
                return@withLock ip
            }
        } catch (_: Exception) {}

        null
    }

    /**
     * WiFi manager — set by [ConnectionService] at startup (avoids a static
     * Service reference).
     *
     * `@Volatile` is enough for the assignment itself (audit A-M8): the race
     * the audit flagged was not this publication but two concurrent
     * [findCarAdb] sweeps — see [scanMutex].
     */
    @Volatile
    var wifiManager: WifiManager? = null

    /**
     * Last-published snapshot of this device's local IPv4 addresses.
     *
     * Publishing once per network change (A-M8) keeps interface enumeration
     * off the per-call scan path and gives the sweep and the own-IP filter
     * one immutable view — a per-call list could straddle an interface change
     * mid-scan (e.g. the hotspot going down between the enumeration and the
     * /24 batch). Null until first published; [findCarAdb] then falls back to
     * a live walk, so behaviour is correct before the owner publishes too.
     */
    @Volatile
    private var subnetSnapshot: List<String>? = null

    /**
     * Publish the subnet snapshot (thread-safe). Called from onCreate and on
     * network change by the owner of the network state — the ConnectionService
     * call site is wired by the lead.
     */
    fun setLocalSubnetSnapshot(ips: List<String>) {
        // Copy on write: the snapshot is read by scan threads while a new
        // publication may be in flight.
        subnetSnapshot = ips.toList()
    }

    /** Forget the snapshot — the next [findCarAdb] falls back to a live walk. */
    fun clearLocalSubnetSnapshot() {
        subnetSnapshot = null
    }

    private fun getLocalSubnetIps(): List<String> = NetUtil.localIpv4Addresses()

    /**
     * Scans a /24 subnet for the ADB port using parallel concurrent probes.
     * Probes the full 1-254 range in batches of [maxConcurrent], 150ms timeout each.
     */
    private suspend fun probeSubnetConcurrent(
        prefix: String, ownIps: List<String>, maxConcurrent: Int = 32
    ): String? = coroutineScope {
        // Skip .0 (network) and .255 (broadcast), and own IPs
        val ownIpSet = ownIps.toSet()
        val ips = (1..254).map { "$prefix.$it" }.filter { it !in ownIpSet }
        ips.chunked(maxConcurrent).forEach { batch ->
            val results = batch.map { ip ->
                async(Dispatchers.IO) { if (probe(ip, CAR_ADB_PORT)) ip else null }
            }
            results.forEach { deferred ->
                val found = deferred.await()
                if (found != null) {
                    coroutineContext.cancelChildren() // cancel remaining probes
                    return@coroutineScope found
                }
            }
        }
        null
    }

    /**
     * Single port-probe body. Blocking connect with a bounded timeout
     * (audit A-L17): `Socket.connect(remote, timeout)` waits inside the
     * kernel.
     *
     * The previous form opened a non-blocking socket channel and polled
     * `finishConnect` with a `Thread.sleep` between attempts — every one of
     * the 254 sweep IPs (×2 interfaces) cost a wakeup per poll for the whole
     * 500ms deadline on unreachable hosts, on the already loaded IO
     * dispatcher. `connect` gives the same deadline with zero polling, and
     * correctness is unchanged: a refused/silent host still times out to
     * false within [timeoutMs].
     *
     * All probes (the suspend [probe], [probePortSync], the gateway step)
     * route through this single body.
     */
    private fun probePortBlocking(ip: String, port: Int, timeoutMs: Long): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs.toInt()) }
            true
        } catch (_: Exception) { false }
    }

    /** Synchronous port probe with a 500ms timeout — used by the manual install path. */
    fun probePortSync(ip: String, port: Int): Boolean = probePortBlocking(ip, port, 500)

    private suspend fun probePort(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) { probePortBlocking(ip, port, 500) }
}
