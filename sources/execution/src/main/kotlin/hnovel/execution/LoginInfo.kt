package hnovel.execution

import kotlinx.serialization.json.*

/** Account-owned source data. Form projection must never replace the original business payload. */
object LoginInfo {
    private const val MAX_BYTES = 65536

    fun validate(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Login information too large" }
        // Opaque text is supported. Structured input must be valid and bounded before parsing.
        if (text.trimStart().firstOrNull() in listOf('{', '['))
            Json.parseToJsonElement(BridgeWire.validate(text.toByteArray(Charsets.UTF_8), MAX_BYTES))
        return text
    }

    private fun objectValue(text: String?): JsonObject? = text?.let {
        runCatching { Json.parseToJsonElement(validate(it)) as? JsonObject }.getOrNull()
    }

    /** Unsupported roots have no Map projection; non-string business fields are omitted. */
    fun stringFields(text: String?): JsonObject? = objectValue(text)?.let { value ->
        JsonObject(value.filterValues { it is JsonPrimitive && it.isString })
    }

    fun merge(text: String?, values: Map<String, String>): String? {
        if (values.isEmpty()) return text
        val original = objectValue(text).orEmpty()
        val fields = values.mapValues { JsonPrimitive(it.value) }
        if (fields.all { (key, value) -> original[key] == value }) return text
        return validate(JsonObject(original + fields).toString())
    }
}
