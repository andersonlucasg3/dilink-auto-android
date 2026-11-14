package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道内消息编号的唯一性约束。
 *
 * 这些常量是两端 `when (messageType)` 分发的依据：一旦两个语义撞上同一个编号，
 * 编译期不会有任何提示，线上表现为"某条命令被当成另一条执行"（例如把屏电源开关
 * 当成 GO_HOME），极难排查。这里用反射把编号全量取出来钉住唯一性。
 */
class MessageTypeTest {

    private fun bytesOf(obj: Any): Map<String, Byte> =
        obj::class.java.declaredFields
            .filter { it.type == Byte::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(obj) as Byte)
            }

    private fun assertUnique(label: String, map: Map<String, Byte>) {
        val collisions = map.entries.groupBy { it.value }.filter { it.value.size > 1 }
        assertTrue(
            "$label 存在编号冲突: " + collisions.map { (v, e) -> "0x${v.toInt() and 0xFF} -> ${e.map { it.key }}" },
            collisions.isEmpty(),
        )
    }

    @Test fun `ControlMsg 编号唯一`() = assertUnique("ControlMsg", bytesOf(ControlMsg))

    @Test fun `VideoMsg 编号唯一`() = assertUnique("VideoMsg", bytesOf(VideoMsg))

    @Test fun `DataMsg 编号唯一`() = assertUnique("DataMsg", bytesOf(DataMsg))

    @Test fun `InputMsg 编号唯一`() = assertUnique("InputMsg", bytesOf(InputMsg))

    @Test
    fun `SET_DISPLAY_POWER 编号固定且不与既有命令冲突`() {
        // 编号是协议契约，改动等于破坏兼容；钉住具体值
        assertEquals(0x32.toByte(), ControlMsg.SET_DISPLAY_POWER)
    }

    @Test
    fun `REQUEST_SYNC_FRAME 编号固定为 0x33`() {
        // issue #9：车机端索要 IDR 的逃生通道，编号同样是协议契约
        assertEquals(0x33.toByte(), ControlMsg.REQUEST_SYNC_FRAME)
    }

    @Test
    fun `PHONE_STATUS 编号固定为 0x21`() {
        // issue #151：手机电量上行。0x20 已是 NAVIGATION_STATE，0x21 是表内下一个空位；
        // 改动等于破坏兼容（老车机按新类型解码会静默忽略，但新手机与新协议文档对不上）。
        assertEquals(0x21.toByte(), DataMsg.PHONE_STATUS)
    }
}
