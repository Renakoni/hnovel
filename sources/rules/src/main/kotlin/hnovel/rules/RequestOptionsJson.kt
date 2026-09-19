package hnovel.rules

import kotlinx.serialization.json.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Legado's Gson request data grammar, with bounds; never JavaScript object evaluation. */
object RequestOptionsJson {
    private const val MAX_CHARS = 65536
    private const val MAX_DEPTH = 64
    fun parse(text: String, parameters: Map<String, JsonElement> = emptyMap(), maxChars: Int = MAX_CHARS): JsonElement = try {
        require(maxChars > 0 && text.length <= maxChars)
        val prepared = templateValues(text, parameters, maxChars)
        require(prepared.length <= maxChars)
        JsonReader(StringReader(prepared)).use { reader ->
            reader.strictness = Strictness.LENIENT
            fun read(depth: Int): JsonElement = when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    require(depth < MAX_DEPTH)
                    reader.beginObject()
                    val values = linkedMapOf<String, JsonElement>()
                    while (reader.hasNext()) values[reader.nextName()] = read(depth + 1)
                    reader.endObject(); JsonObject(values)
                }
                JsonToken.BEGIN_ARRAY -> {
                    require(depth < MAX_DEPTH)
                    reader.beginArray()
                    val values = mutableListOf<JsonElement>()
                    while (reader.hasNext()) values.add(read(depth + 1))
                    reader.endArray(); JsonArray(values)
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull }
                else -> throw RequestOptionsException()
            }
            read(0).also {
                require(reader.peek() == JsonToken.END_DOCUMENT && it.toString().length <= maxChars)
            }
        }
    } catch (_: Exception) { throw RequestOptionsException() }

    /** Bare template values must become JSON values before Gson sees their braces. */
    private fun templateValues(text: String, parameters: Map<String, JsonElement>, maxChars: Int): String {
        if (parameters.isEmpty() || "{{" !in text) return text
        val result = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
            } else if (char == '"' || char == '\'') quote = char
            else if (text.startsWith("{{", index)) {
                val end = text.indexOf("}}", index + 2)
                require(end >= 0)
                val value = parameters[text.substring(index + 2, end).trim()] ?: throw RequestOptionsException()
                result.append(value); index = end + 2
                require(result.length <= maxChars)
                continue
            }
            result.append(char); index++
            require(result.length <= maxChars)
        }
        return result.toString()
    }

    /** Embedded header/body JSON uses the same bounds before either worker or host dispatch. */
    fun options(text: String, parameters: Map<String, JsonElement> = emptyMap()): JsonObject = try {
        val options = parse(text, parameters).jsonObject.toMutableMap()
        val headerKey = if ("headers" in options) "headers" else "header"
        options[headerKey]?.let { options[headerKey] = headers(it, parameters) }
        val body = options["body"]
        if (body is JsonPrimitive && body.isString && body.content.trimStart().firstOrNull() in setOf('{', '['))
            options["body"] = parse(body.content, parameters)
        require(options["js"] == null || options["js"] is JsonPrimitive)
        JsonObject(options)
    } catch (_: IllegalArgumentException) { throw RequestOptionsException() }

    fun headers(value: JsonElement, parameters: Map<String, JsonElement> = emptyMap()): JsonObject = try {
        (if (value is JsonPrimitive) parse(value.content, parameters) else value).jsonObject.also {
            require(it.values.all { header -> header is JsonPrimitive })
        }
    } catch (_: IllegalArgumentException) { throw RequestOptionsException() }
}

/** Neither a parser's original message nor the request data may escape into diagnostics. */
class RequestOptionsException : IllegalArgumentException("Invalid request options")
