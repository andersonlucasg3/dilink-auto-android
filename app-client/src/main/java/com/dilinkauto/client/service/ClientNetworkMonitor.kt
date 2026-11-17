package com.dilinkauto.client.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.dilinkauto.client.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Monitors WiFi network changes with debounced disconnection/reconnection logic.
 *
 * Extracted from [ConnectionService] (SRP): separates network state observation and
 * debounce timing from the core connection service lifecycle.
 *
 * issue #204：状态不能只靠系统回调驱动。现场 `Network lost: 122` 之后系统再没投递过
 * `onAvailable`，而车机在 4 秒后照常建连 —— 旧实现于是永久停在 lost，`Network` 行永久静默。
 * 现在 debounce 到期时用 [ConnectivityManager.getNetworkCapabilities] 复核链路，复核可用就主动
 * 补一条 available；另外 [onTrafficConfirmed] 允许用真实 TCP 握手把状态拉回可用（回调彻底不来
 * 时的最终兜底）。所有 `Network` 行都带 `callback=` / `netId=` / `state=`，下次现场能直接分清
 * 「回调没来」与「状态被吞」。
 */
class ClientNetworkMonitor(
    private val connectivityManager: ConnectivityManager,
    private val scope: CoroutineScope,
    private val debounceDelayMs: Long = DEFAULT_DEBOUNCE_MS,
    private val listener: Listener
) {
    interface Listener {
        fun onNetworkAvailable(network: Network)
        fun onNetworkLostConfirmed(network: Network)
        fun isConnectionActive(): Boolean
    }

    /** 状态机（issue #204）：只回答「该补 available 还是确认 lost」，不参与重建判据。 */
    private val policy = NetworkRecoveryPolicy(debounceDelayMs)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null

    fun start() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                policy.onAvailable()
                cancelDebounce()
                FileLog.i(
                    TAG,
                    "Network available: $network (callback=onAvailable, netId=${netIdOf(network)}," +
                        " state=${policy.state})",
                )
                listener.onNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                val netId = netIdOf(network)
                if (listener.isConnectionActive()) {
                    val activeNet = connectivityManager.activeNetwork
                    if (activeNet != null && activeNet != network) {
                        FileLog.i(
                            TAG,
                            "Network lost: $network (callback=onLost, netId=$netId, state=${policy.state})" +
                                " — not our active network $activeNet, ignoring",
                        )
                        return
                    }
                }
                if (!policy.onLost(netId, SystemClock.elapsedRealtime())) {
                    FileLog.i(
                        TAG,
                        "Network lost: $network (callback=onLost, netId=$netId, state=${policy.state})" +
                            " — duplicate, debounce already pending",
                    )
                    return
                }
                FileLog.i(
                    TAG,
                    "Network lost: $network (callback=onLost, netId=$netId, state=${policy.state})" +
                        " — debouncing ${debounceDelayMs}ms before reacting",
                )
                cancelDebounce()
                debounceJob = scope.launch {
                    delay(debounceDelayMs)
                    onDebounceExpired(network)
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            FileLog.i(TAG, "Network monitor started (callback=register, request=TRANSPORT_WIFI, state=${policy.state})")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * debounce 到期：复核链路，回调没来也要把 available 补上（issue #204 方案 A）。
     */
    private fun onDebounceExpired(lostNetwork: Network) {
        val usableWifi = findUsableWifiNetwork()
        val usableId = usableWifi?.let { netIdOf(it) }
        val decision = policy.onDebounceExpired(
            nowMs = SystemClock.elapsedRealtime(),
            recheck = NetworkRecoveryPolicy.Recheck(wifiNetId = usableId),
        )
        when (decision) {
            NetworkRecoveryPolicy.Decision.REPORT_AVAILABLE -> {
                FileLog.i(
                    TAG,
                    "Network available: ${usableWifi ?: lostNetwork} (callback=none, source=debounce-recheck," +
                        " netId=$usableId, state=${policy.state})" +
                        " — no onAvailable after lost, recheck says link is usable",
                )
                listener.onNetworkAvailable(usableWifi ?: lostNetwork)
            }
            NetworkRecoveryPolicy.Decision.CONFIRM_LOST -> {
                val lostId = netIdOf(lostNetwork)
                if (usableId != null && usableId != lostId) {
                    // 手机切到了另一条 Wi-Fi：原链路没恢复，状态必须留在 lost，
                    // 否则上层会在错的网段上反复探测。
                    FileLog.w(
                        TAG,
                        "Network lost: $lostNetwork (callback=none, source=debounce-recheck, netId=$lostId," +
                            " recheck=$usableId, state=${policy.state})" +
                            " — Wi-Fi 已切换到另一条网络（netId $lostId→$usableId），不代表原链路恢复",
                    )
                } else {
                    FileLog.i(
                        TAG,
                        "Network lost: $lostNetwork (callback=none, source=debounce-recheck, netId=$lostId," +
                            " recheck=no-wifi, state=${policy.state}) — confirmed after ${debounceDelayMs}ms",
                    )
                }
                listener.onNetworkLostConfirmed(lostNetwork)
            }
            NetworkRecoveryPolicy.Decision.NONE -> {
                FileLog.i(
                    TAG,
                    "Network lost debounce expired (callback=none, netId=${netIdOf(lostNetwork)}," +
                        " recheck=$usableId, state=${policy.state}) — superseded by onAvailable, no action",
                )
            }
        }
    }

    /**
     * 复核当前是否真的有可用 Wi-Fi 链路。
     *
     * 用 `allNetworks` 而不是 `activeNetwork`：手机可能同时连车机 AP / 家用 AP，默认网络是谁
     * 不影响「Wi-Fi 还在」。只要求 `TRANSPORT_WIFI` + `NOT_SUSPENDED`，不要求
     * `NET_CAPABILITY_INTERNET` —— 车机热点常年没有外网，按 INTERNET 判会把局域网链路误判成断开。
     *
     * `allNetworks` 在 API 31 标记 deprecated，官方替代是「按 transport 注册多个回调」；但这里正是
     * 为了「回调可能不来的兜底」才主动枚举，改回调替代方案会与兜底目的冲突，故保留。
     */
    @Suppress("DEPRECATION")
    private fun findUsableWifiNetwork(): Network? = try {
        connectivityManager.allNetworks.firstOrNull { net ->
            val caps = connectivityManager.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        }
    } catch (e: Exception) {
        // 权限被收回 / binder 异常时不能假装「有网」，按复核不可用处理。
        FileLog.w(TAG, "Network recheck failed: ${e.message}")
        null
    }

    /**
     * 真实业务流量自证链路可用（issue #204 方案 B）：车机完成握手即说明 TCP 是通的。
     *
     * 只更新状态与日志，**不**回调 [Listener.onNetworkAvailable] —— 回调和重建决策是两件事，
     * 网络事件不得绕过 #200 的静默门槛直接重建视频通道。
     */
    fun onTrafficConfirmed() {
        if (!policy.onTrafficConfirmed()) return
        cancelDebounce()
        val activeNet = try {
            connectivityManager.activeNetwork
        } catch (e: Exception) {
            FileLog.w(TAG, "activeNetwork unavailable during traffic confirm: ${e.message}")
            null
        }
        FileLog.i(
            TAG,
            "Network available: (callback=none, source=tcp-handshake, netId=${activeNet?.let { netIdOf(it) }}," +
                " state=${policy.state}) — link proven by real traffic",
        )
    }

    fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun stop() {
        cancelDebounce()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }

    companion object {
        private const val TAG = "ClientNetworkMonitor"
        const val DEFAULT_DEBOUNCE_MS = NetworkRecoveryPolicy.DEBOUNCE_MS

        /**
         * `Network.netId` 是 @hide（编译期不可见），而 `Network.toString()` 在 AOSP 里固定是
         * `Network{<netId>}`。抽出来做日志与 lost 去重键。
         */
        private fun netIdOf(network: Network): String =
            network.toString().removePrefix("Network{").removeSuffix("}")
    }
}
