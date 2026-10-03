package com.feiq.droid.bridge

/**
 * 极简 JSON **输出**工具。
 *
 * 桥刻意不引第三方 JSON 库：服务端只需要写 JSON（浏览器发来的操作用表单编码，
 * 不需要 JSON 解析器），手写这点转义就够了，也免得给机顶盒上的部署添依赖。
 */
object Json {

    /** 值可以是 String（自动加引号）、Number/Boolean（原样）、null，或 [Raw]（原样拼接）。 */
    class Raw(val json: String)

    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + value(v) }

    fun arr(items: List<Any?>): String = items.joinToString(",", "[", "]") { value(it) }

    /** 字符串转义。注意：换行会被转成 \n，保证 SSE 的 data 始终是单行。 */
    fun str(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is String -> str(v)
        is Number, is Boolean -> v.toString()
        else -> str(v.toString())
    }
}
