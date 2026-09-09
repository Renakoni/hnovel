package hnovel.rhino

import org.mozilla.javascript.*
import java.util.IdentityHashMap

internal class ResultTooLarge : RuntimeException()
internal class UnsupportedResult : RuntimeException()

/** Checks the actual wire representation as it is produced, including keys and JSON escapes. */
internal class BoundedJsonResult(private val maxChars: Int) {
    private val output = StringBuilder()
    private val ancestors = IdentityHashMap<Scriptable, Boolean>()

    fun encode(value: Any?): String {
        write(value, 0)
        return output.toString()
    }

    private fun append(value: CharSequence) {
        if (value.length > maxChars - output.length) throw ResultTooLarge()
        output.append(value)
    }

    private fun quoted(value: CharSequence) {
        if (value.length > maxChars - output.length) throw ResultTooLarge()
        append("\"")
        value.forEach { char -> append(when (char) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            else -> if (char < ' ' || char.isSurrogate()) "\\u%04x".format(char.code) else char.toString()
        }) }
        append("\"")
    }

    private fun write(value: Any?, depth: Int) {
        if (depth > 64) throw UnsupportedResult()
        when (value) {
            null, Undefined.instance, Scriptable.NOT_FOUND -> append("null")
            is CharSequence -> quoted(value)
            is Boolean -> append(value.toString())
            is Number -> append(if (value.toDouble().isFinite()) Context.toString(value) else "null")
            is NativeArray -> composite(value) {
                if (value.length > maxChars) throw ResultTooLarge()
                append("[")
                for (index in 0 until value.length.toInt()) {
                    if (index > 0) append(",")
                    val element = value.get(index, value)
                    write(if (element is Callable) null else element, depth + 1)
                }
                append("]")
            }
            is NativeObject -> composite(value) {
                append("{")
                var first = true
                for (key in value.ids) {
                    val element = when (key) {
                        is String -> value.get(key, value)
                        is Int -> value.get(key, value)
                        else -> continue
                    }
                    if (element === Undefined.instance || element === Scriptable.NOT_FOUND || element is Callable) continue
                    if (!first) append(",")
                    first = false
                    quoted(key.toString())
                    append(":")
                    write(element, depth + 1)
                }
                append("}")
            }
            else -> throw UnsupportedResult()
        }
    }

    private fun composite(value: Scriptable, block: () -> Unit) {
        if (ancestors.put(value, true) != null) throw UnsupportedResult()
        try { block() } finally { ancestors.remove(value) }
    }
}
