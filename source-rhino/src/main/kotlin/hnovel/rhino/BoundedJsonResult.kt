package hnovel.rhino

import org.mozilla.javascript.*
import java.util.IdentityHashMap
import kotlinx.serialization.json.*

internal class ResultTooLarge : RuntimeException()
internal class UnsupportedResult : RuntimeException()
internal class SerializationCancelled : RuntimeException()

/** Checks the actual wire representation as it is produced, including keys and JSON escapes. */
internal class BoundedJsonResult(private val maxChars: Int) {
    private val output = StringBuilder()
    private val ancestors = IdentityHashMap<Scriptable, Boolean>()

    fun encode(value: Any?): String {
        write(value, 0)
        return output.toString()
    }

    private fun append(value: CharSequence) {
        if (Thread.currentThread().isInterrupted) throw SerializationCancelled()
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
        if (Thread.currentThread().isInterrupted) throw SerializationCancelled()
        if (depth > 64) throw UnsupportedResult()
        when (value) {
            null, Undefined.instance, Scriptable.NOT_FOUND -> append("null")
            is CharSequence -> quoted(value)
            is Boolean -> append(value.toString())
            is Number -> append(if (value.toDouble().isFinite()) Context.toString(value) else "null")
            is ScriptDomValue -> writeJson(ScriptDom.snapshot(value.value), depth)
            is ScriptMapValue -> composite(value) { writeJson(ScriptData.json(value.map), depth) }
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

    fun encodeJson(value: JsonElement): String {
        writeJson(value, 0)
        return output.toString()
    }

    private fun writeJson(value: JsonElement, depth: Int) {
        if (depth > 64) throw UnsupportedResult()
        when (value) {
            JsonNull -> append("null")
            is JsonPrimitive -> if (value.isString) quoted(value.content) else append(value.toString())
            is JsonArray -> { append("["); value.forEachIndexed { i, v -> if (i > 0) append(","); writeJson(v, depth + 1) }; append("]") }
            is JsonObject -> { append("{"); value.entries.forEachIndexed { i, (k, v) -> if (i > 0) append(","); quoted(k); append(":"); writeJson(v, depth + 1) }; append("}") }
        }
    }

    private fun composite(value: Scriptable, block: () -> Unit) {
        if (ancestors.put(value, true) != null) throw UnsupportedResult()
        try { block() } finally { ancestors.remove(value) }
    }
}
