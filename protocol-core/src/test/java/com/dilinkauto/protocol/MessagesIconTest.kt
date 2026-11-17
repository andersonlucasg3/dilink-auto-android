package com.dilinkauto.protocol

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class MessagesIconTest {

    @Test
    fun testAppListMessageRejectsOversizedIcon() {
        val buf = ByteBuffer.allocate(64)
        buf.putShort(1) // count = 1
        // App 1: pkg
        buf.putShort(3)
        buf.put("pkg".toByteArray(Charsets.UTF_8))
        // name
        buf.putShort(4)
        buf.put("name".toByteArray(Charsets.UTF_8))
        // category
        buf.put(AppCategory.OTHER.id.toByte())
        // iconSize: MAX_ICON_BYTES + 1
        buf.putInt(AppIconLimits.MAX_ICON_BYTES + 1)
        buf.flip()

        val data = ByteArray(buf.remaining())
        buf.get(data)

        assertThrows(ProtocolDecodeException::class.java) {
            AppListMessage.decode(data)
        }
    }

    @Test
    fun testAppListMessageRejectsNegativeIconSize() {
        val buf = ByteBuffer.allocate(64)
        buf.putShort(1) // count = 1
        buf.putShort(3)
        buf.put("pkg".toByteArray(Charsets.UTF_8))
        buf.putShort(4)
        buf.put("name".toByteArray(Charsets.UTF_8))
        buf.put(AppCategory.OTHER.id.toByte())
        buf.putInt(-1)
        buf.flip()

        val data = ByteArray(buf.remaining())
        buf.get(data)

        assertThrows(ProtocolDecodeException::class.java) {
            AppListMessage.decode(data)
        }
    }
}
