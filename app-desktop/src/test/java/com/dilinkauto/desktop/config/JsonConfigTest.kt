package com.dilinkauto.desktop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [JsonConfig] 单测：扁平对象的读写往返、转义、数字形态与错误处理。 */
class JsonConfigTest {

    @Test
    fun write_thenParse_roundTripsAllValueKinds() {
        val source = linkedMapOf<String, Any?>(
            "dev_phone_ip" to "192.168.3.206",
            "dev_mode" to false,
            "startup_dpi" to 240,
            "startup_bitrate" to 4_000_000L,
            "ratio" to 1.5,
            "nothing" to null,
        )

        val parsed = JsonConfig.parse(JsonConfig.write(source))

        assertEquals("192.168.3.206", parsed["dev_phone_ip"])
        assertEquals(false, parsed["dev_mode"])
        // 整数一律解析成 Long，避免调用方在 Int/Long 之间猜
        assertEquals(240L, parsed["startup_dpi"])
        assertEquals(4_000_000L, parsed["startup_bitrate"])
        assertEquals(1.5, parsed["ratio"])
        assertTrue(parsed.containsKey("nothing"))
        assertNull(parsed["nothing"])
    }

    @Test
    fun parse_handlesWhitespaceAndEmptyObject() {
        assertEquals(emptyMap<String, Any?>(), JsonConfig.parse("  {  }  "))
        assertEquals(emptyMap<String, Any?>(), JsonConfig.parse("{}"))
        assertEquals(mapOf("a" to 1L), JsonConfig.parse("{ \"a\" : 1 }"))
    }

    @Test
    fun write_escapesSpecialCharacters() {
        val text = "a\"b\\c\nd\te"
        val json = JsonConfig.write(mapOf("k" to text))
        assertEquals(text, JsonConfig.parse(json)["k"])
    }

    @Test
    fun parse_readsEscapes() {
        val parsed = JsonConfig.parse("""{"k":"a\"b\\c\n\u0041"}""")
        assertEquals("a\"b\\c\nA", parsed["k"])
    }

    @Test
    fun parse_readsNegativeAndExponentNumbers() {
        val parsed = JsonConfig.parse("""{"a":-12,"b":-1.5e3}""")
        assertEquals(-12L, parsed["a"])
        assertEquals(-1500.0, parsed["b"])
    }

    @Test
    fun parse_preservesKeyOrder() {
        val keys = JsonConfig.parse("""{"z":1,"a":2,"m":3}""").keys.toList()
        assertEquals(listOf("z", "a", "m"), keys)
    }

    @Test
    fun write_emptyMap() {
        assertEquals("{}", JsonConfig.write(emptyMap()))
    }

    @Test
    fun parse_rejectsMalformedInput() {
        val bad = listOf(
            "", // 空文本
            "{", // 未闭合
            """{"a"}""", // 缺冒号
            """{"a":}""", // 缺值
            """{"a":1,}""", // 尾随逗号
            """["a"]""", // 不是对象
            """{"a":1} extra""", // 多余内容
            """{"a":"\q"}""", // 非法转义
        )
        for (text in bad) {
            val failed = runCatching { JsonConfig.parse(text) }.exceptionOrNull()
            assertTrue("应解析失败: $text", failed is JsonFormatException)
        }
    }
}
