package com.dilinkauto.desktop.config

/** config.json 语法错误。 */
class JsonFormatException(message: String) : Exception(message)

/**
 * 极简扁平 JSON 读写。
 *
 * 只支持 `{ "key": "value", ... }` 这种一层对象，值可以是字符串 / 数字 / 布尔 / null。
 * 刻意不支持嵌套、数组与注释——桌面端的 `config.json` 就是一个扁平键值表，
 * 为它引入 JSON 库（或手写完整 JSON 语法）都不划算。
 *
 * 解析数字时不区分 Int/Long/Double：整数一律给 [Long]，带小数点或指数给 [Double]。
 */
object JsonConfig {

    /** 序列化一个扁平对象。键序按传入 Map 的迭代顺序（建议用 LinkedHashMap）。 */
    fun write(values: Map<String, Any?>): String {
        if (values.isEmpty()) return "{}"
        val body = values.entries.joinToString(",\n") { (key, value) ->
            "  ${quote(key)}: ${literal(value)}"
        }
        return "{\n$body\n}\n"
    }

    /** 解析一个扁平对象；文本非法时抛 [JsonFormatException]。 */
    fun parse(text: String): Map<String, Any?> = Parser(text).parseObject()

    private fun literal(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is String -> quote(value)
        else -> quote(value.toString())
    }

    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                skipWhitespace()
                requireEnd()
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (val ch = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw JsonFormatException("第 ${pos} 字符处期望 ',' 或 '}'，实际是 '$ch'")
                }
            }
            skipWhitespace()
            requireEnd()
            return result
        }

        private fun requireEnd() {
            if (pos < text.length) throw JsonFormatException("第 ${pos + 1} 字符后还有多余内容")
        }

        private fun parseValue(): Any? = when (val ch = peek()) {
            '"' -> parseString()
            't' -> readKeyword("true", true)
            'f' -> readKeyword("false", false)
            'n' -> readKeyword("null", null)
            else -> if (ch == '-' || ch == '+' || ch in '0'..'9') parseNumber()
            else throw JsonFormatException("第 ${pos + 1} 字符处不是合法值：'$ch'")
        }

        private fun <T> readKeyword(keyword: String, value: T): T {
            if (!text.startsWith(keyword, pos)) {
                throw JsonFormatException("第 ${pos + 1} 字符处期望 '$keyword'")
            }
            pos += keyword.length
            return value
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val ch = next()
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(parseEscape())
                    else -> sb.append(ch)
                }
            }
        }

        private fun parseEscape(): Char {
            val esc = next()
            return when (esc) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) throw JsonFormatException("\\u 转义不完整")
                    val hex = text.substring(pos, pos + 4)
                    pos += 4
                    hex.toIntOrNull(16)?.toChar()
                        ?: throw JsonFormatException("非法的 \\u 转义：$hex")
                }
                else -> throw JsonFormatException("非法的转义字符：\\$esc")
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-' || peek() == '+') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val token = text.substring(start, pos)
            if (token.isEmpty()) throw JsonFormatException("第 ${start + 1} 字符处不是合法数字")
            return if (token.any { it == '.' || it == 'e' || it == 'E' }) {
                token.toDoubleOrNull() ?: throw JsonFormatException("非法数字：$token")
            } else {
                token.toLongOrNull() ?: throw JsonFormatException("非法数字：$token")
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char =
            if (pos < text.length) text[pos] else throw JsonFormatException("JSON 文本意外结束")

        private fun next(): Char = peek().also { pos++ }

        private fun expect(expected: Char) {
            val actual = next()
            if (actual != expected) {
                throw JsonFormatException("第 $pos 字符处期望 '$expected'，实际是 '$actual'")
            }
        }
    }
}
