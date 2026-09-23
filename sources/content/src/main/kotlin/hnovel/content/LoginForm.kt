package hnovel.content

import hnovel.rules.RequestOptionsException
import hnovel.rules.RequestOptionsJson
import kotlinx.serialization.json.*
import java.util.UUID

data class LoginField(val name: String, val type: String, val action: String? = null,
    val default: String = "", val choices: List<String> = emptyList(), val label: String = name,
    val viewName: String? = null, val id: String = UUID.randomUUID().toString())
data class LoginForm(val fields: List<LoginField>, val browserUrl: String?, val values: Map<String, String> = emptyMap(),
    val id: String = UUID.randomUUID().toString()) {
    internal fun withValues(saved: Map<String, String>) = copy(values = fields.filter { it.type != "button" }.associate { field ->
        field.name to (saved[field.name]?.takeIf { field.choices.isEmpty() || it in field.choices } ?: field.default)
    })

    internal fun validate(values: Map<String, String>, allowAdditional: Boolean = false) {
        val inputs = fields.filter { it.type != "button" }.associateBy { it.name }
        if (values.size > 128 || values.entries.sumOf { it.key.length.toLong() + it.value.length } > 16384)
            throw SourceContentException(ContentError.Limit, "loginUi.values")
        values.forEach { (name, value) ->
            val field = inputs[name] ?: if (allowAdditional) return@forEach
                else throw SourceContentException(ContentError.InvalidRule, "loginUi.values")
            if (value.length > 4096 || field.choices.isNotEmpty() && value !in field.choices)
                throw SourceContentException(ContentError.InvalidRule, "loginUi.values")
        }
    }

    companion object {
        fun parse(ui: String, loginUrl: String, extended: Boolean = false): LoginForm {
            val rows = if (ui.isBlank() && ui.length <= 65536) JsonArray(emptyList()) else try {
                RequestOptionsJson.parse(ui) as? JsonArray
            } catch (failure: RequestOptionsException) {
                if (failure.limitExceeded) throw SourceContentException(ContentError.Limit, "loginUi")
                null
            }
                ?: throw SourceContentException(ContentError.InvalidRule, "loginUi")
            if (rows.size > 128) throw SourceContentException(ContentError.Limit, "loginUi")
            val occurrences = mutableMapOf<String, Int>()
            val fields = rows.mapIndexed { index, value ->
                fun invalid(key: String): Nothing = throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].$key")
                val row = value as? JsonObject ?: invalid("row")
                // Like RuleDiscoverySession.rows in RuleDiscovery.kt, accept style while the host owns
                // layout. Keep this compatibility decision aligned when tightening either row schema.
                val allowed = setOf("name", "type", "action", "style") + if (extended) setOf("default", "chars", "viewName") else emptySet()
                row.keys.firstOrNull { it !in allowed }?.let { invalid(it) }
                fun string(key: String): String? = row[key]?.takeUnless { it == JsonNull }?.let {
                    if (it !is JsonPrimitive || !it.isString) invalid(key)
                    it.content
                }
                val name = string("name")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: invalid("name")
                val type = string("type") ?: "text"
                if (type !in setOf("text", "password", "button") && (!extended || type !in setOf("toggle", "select"))) invalid("type")
                val choices = row["chars"]?.takeUnless { it == JsonNull }?.let { chars ->
                    (chars as? JsonArray ?: invalid("chars")).filter { it != JsonNull }.map {
                        if (it !is JsonPrimitive || !it.isString || it.content.length > 4096) invalid("chars")
                        it.content
                    }
                }.orEmpty()
                if (choices.size > 64 || choices.distinct().size != choices.size ||
                    type in setOf("toggle", "select") && choices.isEmpty()) invalid("chars")
                val default = string("default") ?: choices.firstOrNull().orEmpty()
                if (default.length > 4096 || choices.isNotEmpty() && default !in choices) invalid("default")
                val action = string("action")
                // Buttons are identified by their action, inputs by their binding and action.
                // Labels and row position cannot redirect an existing control to another action.
                val identity = JsonArray(listOf(type, if (type == "button") "" else name, action).map { it?.let(::JsonPrimitive) ?: JsonNull })
                val key = UUID.nameUUIDFromBytes(identity.toString().toByteArray(Charsets.UTF_8)).toString()
                val occurrence = occurrences.getOrDefault(key, 0)
                occurrences[key] = occurrence + 1
                LoginField(name, type, action, default, choices, viewName = string("viewName"), id = "$key:$occurrence")
            }
            // Reference inputs share a name-keyed map. Compatible duplicate bindings share one
            // value; conflicting defaults or options are rejected instead of last-row overwrite.
            fields.filter { it.type != "button" }.groupBy { it.name }.values.forEach { bindings ->
                if (bindings.map { Triple(it.type, it.default, it.choices) }.distinct().size != 1)
                    throw SourceContentException(ContentError.InvalidRule, "loginUi.name")
            }
            val browser = loginUrl.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            return LoginForm(fields, browser).withValues(emptyMap()).also { it.validate(it.values) }
        }
    }
}
