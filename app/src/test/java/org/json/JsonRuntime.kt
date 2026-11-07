package org.json

/**
 * org.json 测试夹具的内部工具：递归下降 JSON 解析器 + 序列化器。
 *
 * ## 设计边界（为什么这么简陋是**对的**）
 *
 * 只服务 `SettingsStore` 的快照编解码与单测输入。输入要么是自己 encode 出来的
 * （合法 JSON），要么是测试里手写的边界样本（截断 / 类型错乱 / 非 JSON）。
 * 解析器只需要两个性质：
 * 1. **合法输入精确解析**（往返无损）
 * 2. **非法输入抛 [JSONException]**（decode 的 runCatching 降级路径依赖它：
 *    「坏备份 → null 而不是启动崩溃」这条产品防线就是建立在解析必抛之上的）
 *
 * ## CJK 不转义（关键取舍）
 *
 * `toString()` 只转义 `"` `\` 与 <0x20 控制字符。快照里全是中文歌单名/文件夹名，
 * 若按某些实现转成 \uXXXX，用户的备份文件会变成肉眼不可读的一坨、
 * diff/code review 也无从审起。json.org 原版的序列化同样不转义非 ASCII。
 */
internal class JsonParser(private val src: String) {
    private var pos = 0

    fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    fun expectEnd() {
        if (pos < src.length) throw JSONException("Unexpected trailing content at $pos")
    }

    fun expectObjectStart() {
        if (pos >= src.length || src[pos] != '{') throw JSONException("Expected '{' at $pos")
        pos++
    }

    fun expectArrayStart() {
        if (pos >= src.length || src[pos] != '[') throw JSONException("Expected '[' at $pos")
        pos++
    }

    /** 已消费 '{' 或 '['，把余下的成员/元素填进 [target] */
    fun parseInto(target: Any) {
        when (target) {
            is JSONObject -> parseMembers(target)
            is JSONArray -> parseElements(target)
            else -> throw JSONException("Unsupported parse target")
        }
    }

    private fun parseMembers(target: JSONObject) {
        skipWhitespace()
        if (peekIs('}')) { pos++; return }
        while (true) {
            skipWhitespace()
            if (pos >= src.length) throw JSONException("Unexpected end in object")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            target.put(key, parseValue())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return
                else -> throw JSONException("Expected ',' or '}' at ${pos - 1}")
            }
        }
    }

    private fun parseElements(target: JSONArray) {
        skipWhitespace()
        if (peekIs(']')) { pos++; return }
        while (true) {
            skipWhitespace()
            if (pos >= src.length) throw JSONException("Unexpected end in array")
            target.put(parseValue())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return
                else -> throw JSONException("Expected ',' or ']' at ${pos - 1}")
            }
        }
    }

    private fun parseValue(): Any {
        skipWhitespace()
        if (pos >= src.length) throw JSONException("Unexpected end of input")
        return when (val c = src[pos]) {
            '{' -> {
                pos++
                val o = JSONObject()
                parseMembers(o)
                o
            }
            '[' -> {
                pos++
                val a = JSONArray()
                parseElements(a)
                a
            }
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", JSONObject.NULL)
            else -> {
                if (c == '-' || c in '0'..'9') parseNumber()
                else throw JSONException("Unexpected char '$c' at $pos")
            }
        }
    }

    private fun parseLiteral(word: String, value: Any): Any {
        if (!src.startsWith(word, pos)) throw JSONException("Invalid literal at $pos")
        pos += word.length
        return value
    }

    private fun parseNumber(): Any {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] in ".eE+-")) pos++
        val text = src.substring(start, pos)
        // 有小数点/指数 → Double；否则 Long（Int 范围内 optInt 经 toInt() 无损取出）。
        // 无效数字必须抛而不是 0 —— 静默 0 会把「损坏」伪装成「合法的零值」。
        return if ('.' in text || 'e' in text || 'E' in text) {
            text.toDoubleOrNull() ?: throw JSONException("Invalid number '$text'")
        } else {
            text.toLongOrNull() ?: throw JSONException("Invalid number '$text'")
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw JSONException("Unterminated string")
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw JSONException("Unterminated escape")
                    when (val e = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        // Form feed 字面量在源码里不可靠（编译器差异），用 0x0C 显式表达
                        'f' -> sb.append(0x0C.toChar())
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw JSONException("Bad unicode escape")
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            val code = hex.toIntOrNull(16) ?: throw JSONException("Bad unicode escape '$hex'")
                            sb.append(code.toChar())
                        }
                        else -> throw JSONException("Bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun expect(c: Char) {
        if (pos >= src.length || src[pos] != c) throw JSONException("Expected '$c' at $pos")
        pos++
    }

    private fun next(): Char {
        if (pos >= src.length) throw JSONException("Unexpected end of input")
        return src[pos++]
    }

    private fun peekIs(c: Char): Boolean = pos < src.length && src[pos] == c
}

/** JSON 序列化器：字符串转义 + 值分派。 */
internal object JsonWriter {
    fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> {
                // 对齐 json.org：整数值 Double 去小数点（0.0 → "0"）；
                // NaN/Infinity 不是合法 JSON，必须抛而不是输出垃圾
                if (v.isNaN() || v.isInfinite()) throw JSONException("Forbidden numeric value: $v")
                if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
                    sb.append(v.toLong().toString())
                } else {
                    sb.append(v.toString())
                }
            }
            is Float -> writeValue(sb, v.toDouble())
            else -> sb.append(v.toString()) // JSONObject / JSONArray / NULL 哨兵都有正确的 toString
        }
    }

    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == 0x0C.toChar() -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c) // CJK 与一切可打印字符原样输出
            }
        }
        sb.append('"')
    }
}
