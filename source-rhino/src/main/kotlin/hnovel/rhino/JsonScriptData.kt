package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** Constructs JS data without javaToJS wrappers, evaluating source, or consulting mutable JSON globals. */
internal class JsonScriptData(private val context: Context, private val scope: Scriptable, private val maxChars: Int) {
    private var remaining = maxChars

    fun convert(value: JsonElement): Any? {
        val converted = read(value, 0)
        // Also count actual JSON escaping/punctuation; the early budget bounds intermediate objects.
        BoundedJsonResult(maxChars).encode(converted)
        return converted
    }

    private fun charge(size: Int) {
        if (Thread.currentThread().isInterrupted) throw SerializationCancelled()
        if (size > remaining) throw ResultTooLarge()
        remaining -= size
    }

    private fun read(value: JsonElement, depth: Int): Any? {
        if (depth > 64) throw UnsupportedResult()
        charge(1)
        return when (value) {
            JsonNull -> null
            is JsonPrimitive -> {
                charge(value.content.length)
                when {
                    value.isString -> value.content
                    value.booleanOrNull != null -> value.boolean
                    else -> value.double
                }
            }
            is JsonArray -> {
                if (value.size > remaining) throw ResultTooLarge()
                ScriptRealm.current(context).arrayIn(scope, value.map { read(it, depth + 1) }.toTypedArray())
            }
            is JsonObject -> ScriptRealm.current(context).objectIn(scope).apply {
                for ((key, item) in value) {
                    charge(key.length)
                    defineProperty(key, read(item, depth + 1), ScriptableObject.EMPTY)
                }
            }
        }
    }
}
