package hnovel.imports

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.serialization.json.*
import java.io.StringReader

/** Streaming validation catches duplicate fields and bounds nesting before constructing a JSON tree. */
internal fun parseDefinitionJson(text: String, maxDepth: Int): JsonElement {
    JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
        reader.strictness = Strictness.STRICT
        fun read(depth: Int): JsonElement {
            if (depth > maxDepth) throw ImportFailure(ImportCode.TooDeep)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val fields = linkedMapOf<String, JsonElement>()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in fields) throw ImportFailure(ImportCode.DuplicateField)
                        fields[name] = read(depth + 1)
                    }
                    reader.endObject()
                    JsonObject(fields)
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val values = mutableListOf<JsonElement>()
                    while (reader.hasNext()) values.add(read(depth + 1))
                    reader.endArray()
                    JsonArray(values)
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull }
                else -> throw ImportFailure(ImportCode.InvalidJson)
            }
        }
        val result = read(0)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw ImportFailure(ImportCode.InvalidJson)
        return result
    }
}

data class ParsedSource(val key: String, val name: String, val enabled: Boolean, val enabledExplore: Boolean,
    val notices: List<ImportNotice>)

/** Adapters recognize/validate definitions; they never create an execution engine. */
interface SourceFormatAdapter {
    val format: String
    val profiles: Set<String>
    fun recognizes(value: JsonObject): Boolean
    fun validate(value: JsonObject): ParsedSource
}

class LegadoSourceAdapter : SourceFormatAdapter {
    override val format = "legado-json"
    override val profiles = setOf(LEGADO_PROFILE, EXTENSION_PROFILE)
    override fun recognizes(value: JsonObject) = "bookSourceUrl" in value || "bookSourceName" in value

    override fun validate(value: JsonObject): ParsedSource {
        fun string(key: String): String? {
            val element = value[key] ?: return null
            if (element == JsonNull) return null
            if (element !is JsonPrimitive || !element.isString) throw ImportFailure(ImportCode.InvalidField, key)
            return element.content
        }
        fun boolean(key: String, default: Boolean): Boolean {
            val element = value[key] ?: return default
            if (element == JsonNull) return default
            return (element as? JsonPrimitive)?.booleanOrNull ?: throw ImportFailure(ImportCode.InvalidField, key)
        }
        fun number(key: String): Long? {
            val element = value[key] ?: return null
            if (element == JsonNull) return null
            return (element as? JsonPrimitive)?.longOrNull ?: throw ImportFailure(ImportCode.InvalidField, key)
        }
        val key = string("bookSourceUrl")?.takeIf { it.isNotBlank() }
            ?: throw ImportFailure(ImportCode.MissingIdentity, "bookSourceUrl")
        val name = string("bookSourceName")?.takeIf { it.isNotBlank() } ?: key
        if ((number("bookSourceType") ?: 0) != 0L) throw ImportFailure(ImportCode.UnsupportedType, "bookSourceType")
        val enabled = boolean("enabled", true)
        val explore = boolean("enabledExplore", true)
        boolean("enabledCookieJar", true)
        val strings = setOf("bookSourceGroup", "bookUrlPattern", "jsLib", "header",
            "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs", "bookSourceComment", "variableComment",
            "exploreUrl", "exploreScreen", "searchUrl")
        strings.forEach(::string)
        val numbers = setOf("concurrentRate", "customOrder", "lastUpdateTime", "respondTime", "weight")
        numbers.forEach(::number)
        val rules = setOf("ruleExplore", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleReview")
        rules.forEach { field ->
            val rule = value[field]
            if (rule != null && rule != JsonNull && rule !is JsonObject) throw ImportFailure(ImportCode.InvalidField, field)
        }
        val known = strings + numbers + rules + setOf("bookSourceUrl", "bookSourceName", "bookSourceType", "enabled", "enabledExplore", "enabledCookieJar")
        val notices = value.keys.filter { it !in known }.map { ImportNotice("UnclassifiedField", it) }.toMutableList()
        // Parsing a rule object is not a claim that its fields or scripts are executable.
        notices.add(ImportNotice("ExecutionCompatibilityPending"))
        if ("customOrder" in value) notices.add(ImportNotice("ExternalOrderRetainedNotApplied", "customOrder"))
        return ParsedSource(key, name, enabled, explore, notices)
    }
}

internal fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
}
