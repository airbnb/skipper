package com.airbnb.skipper.testutils.trace

/**
 * The few JSON shapes a trace line holds (maps, lists, strings, numbers, booleans, null), encoded
 * without pulling a JSON library into the published test harness.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> out.append(value.toString())
            is String -> string(out, value)
            is Enum<*> -> string(out, value.name)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(
        out: StringBuilder,
        s: String,
    ) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
