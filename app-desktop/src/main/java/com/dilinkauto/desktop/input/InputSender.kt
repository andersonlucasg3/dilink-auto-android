package com.dilinkauto.desktop.input

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import java.io.IOException

/**
 * 桌面端输入编码器：把鼠标手势 / 导航命令编码成协议帧并投递到手机的输入口（9639）。
 *
 * 职责边界（SRP）：只做"输入语义 → 协议帧"，不持有连接、不碰 UI。
 * 真正的字节发送由会话层通过 [transport] 注入——这样本类可以脱离网络单测，
 * 而连接生命周期仍由 [com.dilinkauto.desktop.DesktopConnectionService] 独占管理。
 *
 * 协议分工（与手机侧 `PipelineServer.readTouchAndCommands` / `TouchInjector` 对齐）：
 *  - 触摸：`Channel.INPUT` + `TOUCH_DOWN / TOUCH_MOVE_BATCH / TOUCH_UP`；
 *  - 命令：`Channel.CONTROL` + `GO_HOME / GO_BACK / GO_RECENT / LAUNCH_APP / SET_DISPLAY_POWER`
 *    （这些命令走输入口而不是控制口，手机侧注释已明确此约定）。
 *
 * ── transport 抛错的处理（audit D-L4）──
 * [transport] 是 EDT 同步调用的（鼠标监听器在 EDT 上），而底层
 * `Connection.sendFrame` 在连接已断开时会抛 IOException。此前这个异常会从
 * MouseAdapter 里逃出去：AWT 打一栈（用户视角"抖一下"），而 [pressed] 还停在
 * true —— 下一次 MOVE 又会尝试发送，手机上出现"粘指"（指针从不抬起）。
 * 现在所有发送都经 [send]，异常统一当作"传输已消失"处理：清 [transport] 与
 * [pressed]，后续事件按"未连接"返回 false。
 */
class InputSender(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * 帧发送通道。会话连上输入口后由会话层注入；断开时置空。
     * 参数为 (channel, messageType, payload)，与会话层的 `Connection.sendFrame` 一一对应。
     */
    @Volatile
    var transport: ((channel: Byte, messageType: Byte, payload: ByteArray) -> Unit)? = null

    /** 当前是否有按下的指针。未按下时不发 MOVE，避免手机侧出现无来源的移动。 */
    private var pressed = false

    val isConnected: Boolean get() = transport != null

    /** 左键按下。返回 false 表示当前无输入连接、事件已丢弃。 */
    fun down(x: Float, y: Float): Boolean {
        val t = transport ?: return false
        pressed = true
        val sent = send(t, Channel.INPUT, InputMsg.TOUCH_DOWN, touch(InputMsg.TOUCH_DOWN, x, y).encode())
        if (!sent) pressed = false
        return sent
    }

    /** 拖动。走批量 MOVE（单指也批量），与手机侧的批量注入路径一致。 */
    fun move(x: Float, y: Float): Boolean {
        if (!pressed) return false
        val t = transport ?: return false
        val batch = TouchMoveBatch(listOf(touch(InputMsg.TOUCH_MOVE, x, y)))
        return send(t, Channel.INPUT, InputMsg.TOUCH_MOVE_BATCH, batch.encode())
    }

    /** 抬起。若此前未按下则忽略（避免重复 UP）。 */
    fun up(x: Float, y: Float): Boolean {
        if (!pressed) return false
        val t = transport ?: return false
        pressed = false
        return send(t, Channel.INPUT, InputMsg.TOUCH_UP, touch(InputMsg.TOUCH_UP, x, y).encode())
    }

    fun goHome(): Boolean = sendCommand(ControlMsg.GO_HOME)

    fun goBack(): Boolean = sendCommand(ControlMsg.GO_BACK)

    fun goRecent(): Boolean = sendCommand(ControlMsg.GO_RECENT)

    fun launchApp(packageName: String): Boolean =
        sendCommand(ControlMsg.LAUNCH_APP, LaunchAppMessage(packageName).encode())

    /**
     * 开关手机**物理**屏（payload 1 字节：1=亮屏、0=熄屏）。
     *
     * 连上后 vd-server 会**自动**熄屏，这条只用于中途手动干预；与导航命令同走
     * 输入口的 CONTROL 通道，由手机侧 `PipelineServer.handleCarCommand` 执行。
     */
    fun setDisplayPower(on: Boolean): Boolean =
        sendCommand(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(if (on) 1 else 0))

    private fun sendCommand(messageType: Byte, payload: ByteArray = ByteArray(0)): Boolean {
        val t = transport ?: return false
        return send(t, Channel.CONTROL, messageType, payload)
    }

    /**
     * 真正投递一帧（audit D-L4）。
     *
     * `Connection.sendFrame` 在连接断开时会抛 IOException（ProtocolDecodeException
     * 之外的发送失败路径）。异常一律吞掉并按"传输已消失"处理：清 transport 与
     * pressed。**不能**让异常从鼠标监听器里逃出去 —— 那会让 [pressed] 卡在 true。
     *
     * @return 是否成功送达
     */
    private fun send(
        transport: (Byte, Byte, ByteArray) -> Unit,
        channel: Byte,
        messageType: Byte,
        payload: ByteArray,
    ): Boolean = try {
        transport(channel, messageType, payload)
        true
    } catch (_: IOException) {
        transportGone()
        false
    } catch (_: Exception) {
        // 兜底：任何 Leiden 异常都按"通道死了"处理，绝不放它回到 EDT 的监听器上。
        transportGone()
        false
    }

    /** 传输消失了：摘掉通道并清按压状态。 */
    private fun transportGone() {
        transport = null
        pressed = false
    }

    /** 清除按压状态。会话断开/重连时调用，避免新会话误发 MOVE。 */
    fun reset() {
        pressed = false
    }

    private fun touch(action: Byte, x: Float, y: Float) = TouchEvent(
        action = action,
        pointerId = POINTER_ID,
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        pressure = PRESSURE,
        timestamp = clock(),
    )

    companion object {
        /** 鼠标只有单指针，固定 0；手机侧按 pointerId 维护 activePointers。 */
        private const val POINTER_ID = 0
        private const val PRESSURE = 1.0f
    }
}
