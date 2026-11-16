package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「网络变化 → 是否重建投屏通道」的纯函数（issue #200）。
 *
 * 现场（`.tmp/run-1116/client.log`）：
 * ```
 * 16:36:09.248 [Network] Network available: 122
 * 16:36:09.255 [Network] Local IPv4 snapshot: ...
 * 16:36:16.022 [VideoDecoder][W] STALL 5983ms ...
 * ```
 * `onNetworkAvailable` 在 `STREAMING` 状态下什么都没做（只刷了网段快照），链路静默
 * 5.98s 只能等它自己恢复。但**无条件重建同样是错的**：同一份日志里全量重建
 * （`cleanupSession` → `VD server lifecycle connected`）实测 3.7~4.9s，而且断链期间
 * 连握手都完不成 —— 在 available 报到的瞬间拆会话只会把 6s 冻屏拉成 10s 以上。
 *
 * 所以判据是「链路已恢复（available 已到）但控制通道仍在静默」：心跳 3s 一拍
 * （`ConnectionKeepAlive.DEFAULT_HEARTBEAT_INTERVAL_MS`），静默 ≥6s 说明连续漏了两拍，
 * 这个 socket 已经半死，只能重建。
 */
class NetworkRebuildPolicyTest {

    private fun policy() = NetworkRebuildPolicy()

    @Test
    fun `现场 5_98 秒空档里 available 只到 1_96 秒静默——不得重建`() {
        val p = policy()
        // 09.248 available 时，控制通道最后一次收到帧是 07.291
        assertEquals(
            NetworkRebuildPolicy.Action.NONE,
            p.onNetworkAvailable(nowMs = 1_000_000, streaming = true, controlSilenceMs = 1_960),
        )
    }

    @Test
    fun `链路已恢复但控制通道连续漏掉两拍心跳才重建`() {
        val p = policy()
        assertEquals(
            NetworkRebuildPolicy.Action.NONE,
            p.onNetworkAvailable(nowMs = 1_000_000, streaming = true, controlSilenceMs = 5_999),
        )
        assertEquals(
            NetworkRebuildPolicy.Action.REBUILD,
            p.onNetworkAvailable(nowMs = 1_001_000, streaming = true, controlSilenceMs = 6_000),
        )
    }

    @Test
    fun `冷却期内不重复重建`() {
        val p = policy()
        assertEquals(
            NetworkRebuildPolicy.Action.REBUILD,
            p.onNetworkAvailable(nowMs = 10_000, streaming = true, controlSilenceMs = 8_000),
        )
        // 重关联时 onAvailable 会连续报到；冷却窗口内一律不动，避免拆完再拆
        assertEquals(
            NetworkRebuildPolicy.Action.NONE,
            p.onNetworkAvailable(nowMs = 12_000, streaming = true, controlSilenceMs = 9_000),
        )
        assertEquals(
            NetworkRebuildPolicy.Action.REBUILD,
            p.onNetworkAvailable(
                nowMs = 10_000 + NetworkRebuildPolicy.REBUILD_COOLDOWN_MS,
                streaming = true,
                controlSilenceMs = 8_000,
            ),
        )
    }

    @Test
    fun `非投屏状态不重建`() {
        val p = policy()
        assertEquals(
            NetworkRebuildPolicy.Action.NONE,
            p.onNetworkAvailable(nowMs = 1_000, streaming = false, controlSilenceMs = 30_000),
        )
    }

    @Test
    fun `没有控制连接时不做判断`() {
        val p = policy()
        assertEquals(
            NetworkRebuildPolicy.Action.NONE,
            p.onNetworkAvailable(nowMs = 1_000, streaming = true, controlSilenceMs = -1),
        )
    }

    @Test
    fun `用户停止后冷却期复位`() {
        val p = policy()
        p.onNetworkAvailable(nowMs = 10_000, streaming = true, controlSilenceMs = 8_000)
        p.reset()
        assertEquals(
            NetworkRebuildPolicy.Action.REBUILD,
            p.onNetworkAvailable(nowMs = 10_500, streaming = true, controlSilenceMs = 8_000),
        )
    }

    @Test
    fun `默认阈值与心跳节奏对齐`() {
        // 心跳 3s：静默 6s = 连续漏两拍
        assertEquals(6_000L, NetworkRebuildPolicy.DEAD_LINK_SILENCE_MS)
        assertEquals(30_000L, NetworkRebuildPolicy.REBUILD_COOLDOWN_MS)
    }
}
