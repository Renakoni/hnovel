package hnovel.rules

import kotlinx.serialization.json.*

/** Request data grammar: JSON plus single-quoted strings, never JavaScript object evaluation. */
object RequestOptionsJson {
    private const val MAX_CHARS = 65536
    private const val MAX_DEPTH = 64
    private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun parse(text: String): JsonElement = try {
        require(text.length <= MAX_CHARS)
        val json = StringBuilder(text.length)
        val literal = StringBuilder()
        fun finishLiteral() {
            if (literal.isEmpty()) return
            val value = literal.toString()
            require(value in setOf("null", "true", "false") || number.matches(value))
            literal.setLength(0)
        }
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (char in text) {
            // The JSON tree parser accepts bare scalar tokens and discards overwritten keys.
            // Check every literal before parsing, including values behind duplicate keys.
            if (quote == null) {
                if (char in "{}[]:, \t\r\n\"'") finishLiteral() else literal.append(char)
            }
            if (quote != null) {
                require(char >= ' ')
                when {
                    escaped -> {
                        if (char == '\'' && quote == '\'') json.append(char)
                        else json.append('\\').append(char)
                        escaped = false
                    }
                    char == '\\' -> escaped = true
                    char == quote -> { quote = null; json.append('"') }
                    char == '"' -> json.append("\\\"")
                    else -> json.append(char)
                }
            } else when (char) {
                '"', '\'' -> { quote = char; json.append('"') }
                '{', '[' -> { require(++depth <= MAX_DEPTH); json.append(char) }
                '}', ']' -> { require(--depth >= 0); json.append(char) }
                else -> { require(char >= ' ' || char in "\t\r\n"); json.append(char) }
            }
            require(json.length <= MAX_CHARS)
        }
        require(quote == null && !escaped && depth == 0)
        finishLiteral()
        Json.parseToJsonElement(json.toString())
    } catch (_: IllegalArgumentException) { throw RequestOptionsException() }

    /** Embedded header/body JSON uses the same bounds before either worker or host dispatch. */
    fun options(text: String): JsonObject = try {
        val options = parse(text).jsonObject.toMutableMap()
        val headerKey = if ("headers" in options) "headers" else "header"
        options[headerKey]?.let { options[headerKey] = headers(it) }
        val body = options["body"]
        if (body is JsonPrimitive && body.isString && body.content.trimStart().firstOrNull() in setOf('{', '['))
            options["body"] = parse(body.content)
        require(options["js"] == null || options["js"] is JsonPrimitive)
        JsonObject(options)
    } catch (_: IllegalArgumentException) { throw RequestOptionsException() }

    fun headers(value: JsonElement): JsonObject = try {
        (if (value is JsonPrimitive) parse(value.content) else value).jsonObject.also {
            require(it.values.all { header -> header is JsonPrimitive })
        }
    } catch (_: IllegalArgumentException) { throw RequestOptionsException() }
}

/** Neither a parser's original message nor the request data may escape into diagnostics. */
class RequestOptionsException : IllegalArgumentException("Invalid request options")
