package gsp

/** A small JSON reader and writer (objects are Map, arrays are List, numbers are Long or Double). */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skip()
        val value = p.value()
        p.skip()
        if (p.pos != text.length) error("Unexpected text after JSON value")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) java.math.BigDecimal.valueOf(value).toPlainString() else "null")
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, v)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skip() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skip()
            if (pos >= s.length) error("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, pos)) error("Bad literal at $pos")
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skip()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skip()
                val key = str()
                skip()
                if (s[pos] != ':') error("Expected ':' at $pos")
                pos++
                map[key] = value()
                skip()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skip()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list += value()
                skip()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun str(): String {
            if (s[pos] != '"') error("Expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?>? = this as? List<Any?>

fun Any?.text(): String = when (this) {
    null -> ""
    is String -> this
    else -> toString()
}
