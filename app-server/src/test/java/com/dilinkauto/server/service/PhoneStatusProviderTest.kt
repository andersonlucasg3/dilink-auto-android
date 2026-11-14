package com.dilinkauto.server.service

import com.dilinkauto.protocol.PhoneStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车机侧电量状态的唯一写入路径（issue #151，[PhoneStatusProvider]）。
 *
 * 钉住三件事：收到合法状态后 UI 状态跟着变；断开会话 [PhoneStatusProvider.clear]
 * 回到 null（chip 渲染 `—`，不残留上一台手机的电量）；[PhoneStatusProvider.stop]
 * 同样清空，避免服务重启后旧值闪现。
 */
class PhoneStatusProviderTest {

    private val sink = MutableStateFlow<PhoneStatusUiState?>(null)
    private val provider = PhoneStatusProvider(sink)

    @Test
    fun `初始状态为 null`() {
        assertNull(provider.state.value)
    }

    @Test
    fun `收到状态后暴露 level 与 charging`() {
        provider.onStatus(PhoneStatus(level = 86, charging = true, temperatureC = 30))
        assertEquals(PhoneStatusUiState(level = 86, charging = true), provider.state.value)
    }

    @Test
    fun `clear 后回到 null`() {
        provider.onStatus(PhoneStatus(level = 42, charging = false))
        provider.clear()
        assertNull(provider.state.value)
    }

    @Test
    fun `stop 清空状态`() {
        provider.onStatus(PhoneStatus(level = 42, charging = false))
        provider.stop()
        assertNull(provider.state.value)
    }

    @Test
    fun `start 从干净状态开始`() {
        sink.value = PhoneStatusUiState(level = 99, charging = false)
        provider.start()
        assertNull(provider.state.value)
    }
}
