package hnovel.content

import hnovel.rules.RuleValue
import kotlinx.serialization.json.*

data class RuleDiscoveryEnvironment(val themeMode: String = "0", val theme: JsonObject = JsonObject(emptyMap()),
    val reading: JsonObject = JsonObject(emptyMap()))
data class RuleDiscoveryRow(val id: String, val title: String, val type: String, val url: String = "",
    val action: String = "", val choices: List<String> = emptyList(), val default: String = "", val field: String = "exploreUrl",
    val viewName: String = "")
data class RuleDiscoveryCatalog(val rows: List<RuleDiscoveryRow>, val values: Map<String, String>)
data class RuleDiscoveryAction(val kind: String, val value: String = "", val title: String = "",
    val html: String? = null, val script: String = "")
data class RuleDiscoveryUpdate(val catalog: RuleDiscoveryCatalog, val actions: List<RuleDiscoveryAction>, val refresh: Boolean)

/** A page-local draft. Explicit infoMap saves are source settings; no feed or cursor is persisted. */
class RuleDiscoverySession internal constructor(private val source: RuleSource, val id: String,
    initialValues: Map<String, String>, private val environment: RuleDiscoveryEnvironment) {
    private var values = initialValues.toMap()
    private var initialized = false
    private var saveSeconds: Long? = null
    private var current: RuleDiscoveryCatalog? = null
    init { require(id.isNotBlank() && id.length <= 128); validateValues(values) }

    suspend fun catalog(refresh: Boolean = false): RuleDiscoveryCatalog = source.operation("exploreUrl") {
        if (!source.canDiscover) throw SourceContentException(ContentError.MissingCapability, "exploreUrl")
        if (!refresh) current?.let { return@operation it }
        val context = context()
        val rows = rows(context, source.spec.exploreUrl, "exploreUrl") +
            source.spec.exploreScreen.takeIf(String::isNotBlank)?.let { rows(context, it, "exploreScreen") }.orEmpty()
        if (source.spec.customButton) {
            if (!source.spec.eventListener || source.spec.content.string("callBackJs").isBlank())
                throw SourceContentException(ContentError.InvalidRule, "ruleContent.callBackJs")
        }
        val all = rows + if (source.spec.customButton) listOf(RuleDiscoveryRow("custom-button", "Source action", "button",
            action = source.spec.content.string("callBackJs"), field = "ruleContent.callBackJs")) else emptyList()
        if (all.size > MAX_CATALOG_ROWS) throw SourceContentException(ContentError.Limit, "exploreUrl")
        if (all.map { it.id }.distinct().size != all.size)
            throw SourceContentException(ContentError.InvalidRule, "exploreUrl.id")
        val draft = readValues(context).toMutableMap()
        all.filter { it.type in inputTypes }.forEach { row ->
            val value = draft[row.id] ?: row.default
            draft[row.id] = if (row.choices.isNotEmpty() && value !in row.choices) row.default else value
        }
        // A new definition may remove controls. Their old drafts cannot become URL/filter inputs.
        val inputs = all.filter { it.type in inputTypes }.map { it.id }.toSet()
        val filtered = draft.filterKeys { it in inputs || it !in initialInputKeys }
        context.discovery = JsonObject(context.discovery!! + ("values" to jsonValues(filtered)))
        val rendered = all.map { row ->
            if (row.viewName.isBlank()) row else {
                val title = script(context, row.viewName, "${row.field}.viewName").jsonPrimitive.content
                if (title.length > 256) throw SourceContentException(ContentError.Limit, "${row.field}.viewName")
                row.copy(title = title)
            }
        }
        capture(context)
        initialInputKeys = inputs
        RuleDiscoveryCatalog(rendered, values.toMap()).also { current = it }
    }

    private var initialInputKeys = emptySet<String>()

    suspend fun interact(rowId: String, value: String? = null, longClick: Boolean = false): RuleDiscoveryUpdate {
        val catalog = catalog()
        return source.operation("discovery.action") {
            val row = catalog.rows.singleOrNull { it.id == rowId }
                ?: throw SourceContentException(ContentError.InvalidRule, "discovery.action.id")
            val next = values.toMutableMap()
            if (value != null) {
                if (row.type !in inputTypes || value.length > 4096 || row.choices.isNotEmpty() && value !in row.choices)
                    throw SourceContentException(ContentError.InvalidRule, "${row.field}.value")
                next[row.id] = value
            } else if (row.type != "button") throw SourceContentException(ContentError.InvalidRule, "${row.field}.value")
            val context = context(interactive = true, draft = next, event = if (rowId == "custom-button")
                if (longClick) "longClickCustomButton" else "clickCustomButton" else null, longClick = longClick)
            if (row.action.isNotBlank()) {
                if (row.action.startsWith("https://", true) || row.action.startsWith("http://", true)) {
                    context.discovery = JsonObject(context.discovery!! + ("actions" to buildJsonArray {
                        add(buildJsonObject { put("kind", "showBrowser"); put("args", buildJsonArray { add(row.action) }) })
                    }))
                } else script(context, row.action, "${row.field}.action")
            }
            val actions = actions(context)
            val refresh = actions.any { it.kind == "refresh" || it.kind == "configuration" }
            capture(context)
            val updated = catalog.copy(values = values.toMap())
            current = if (refresh) null else updated
            RuleDiscoveryUpdate(updated, actions.filterNot { it.kind == "refresh" }, refresh)
        }
    }

    suspend fun page(url: String, page: Int, filters: Map<String, String>): List<RuleBook> = source.operation("ruleExplore") {
        if (!source.canDiscover || source.spec.explore.isEmpty()) throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
        if (page !in 1..64) throw SourceContentException(ContentError.Limit, "ruleExplore.page")
        validateValues(filters)
        source.discoveryPage(context(page = page, draft = values + filters, noBook = false), url)
    }

    private fun context(page: Int = 1, interactive: Boolean = false, draft: Map<String, String> = values,
        event: String? = null, longClick: Boolean = false, noBook: Boolean = true): RuleEvaluation {
        if (!initialized) {
            source.discoveryState("info")?.let { saved ->
                val stored = Json.parseToJsonElement(saved).jsonObject
                val expires = stored["expires"]?.jsonPrimitive?.long ?: 0
                if (expires == 0L || expires > System.currentTimeMillis()) values = stringMap(stored.getValue("values")) + values
            }
            initialized = true
        }
        return source.evaluation(page = page, interactive = interactive).also { context ->
            context.discovery = buildJsonObject {
                put("sessionId", id); put("values", jsonValues(values + draft)); put("interactive", interactive)
                put("event", event?.let(::JsonPrimitive) ?: JsonNull); put("longClick", longClick); put("noBook", noBook)
                put("saveSeconds", saveSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("themeMode", environment.themeMode); put("theme", environment.theme); put("reading", environment.reading)
            }
        }
    }

    private suspend fun script(context: RuleEvaluation, code: String, field: String): JsonElement {
        val result = context.script("JSON.stringify(eval(${JsonPrimitive(scriptBody(code))}))", RuleValue.Empty, field).text()
        return if (result.isBlank()) JsonNull else Json.parseToJsonElement(result)
    }

    private suspend fun rows(context: RuleEvaluation, rule: String, field: String): List<RuleDiscoveryRow> {
        val value = if (rule.trimStart().startsWith("@js:", true) || rule.trimStart().startsWith("<js>", true)) script(context, rule, field)
            else JsonPrimitive(rule)
        val raw = if (value is JsonPrimitive && value.isString) {
            val text = value.content.trim()
            if (text.startsWith('[')) Json.parseToJsonElement(text) else buildJsonArray {
                text.split(Regex("(?:&&|\\r?\\n)+")).filter(String::isNotBlank).forEach { item ->
                    val parts = item.split("::", limit = 2)
                    add(buildJsonObject { put("title", parts[0]); put("url", parts.getOrElse(1) { "" }) })
                }
            }
        } else value
        if (raw !is JsonArray) throw SourceContentException(ContentError.InvalidRule, field)
        if (raw.size > MAX_CATALOG_ROWS) throw SourceContentException(ContentError.Limit, field)
        val occurrences = mutableMapOf<String, Int>()
        return raw.mapIndexedNotNull { index, item ->
            val location = "$field[$index]"
            val row = item as? JsonObject ?: throw SourceContentException(ContentError.InvalidRule, location)
            // Keep style acceptance aligned with LoginForm.parse: sources may declare it, but the
            // host owns layout. Tightening either row schema must preserve this shared decision.
            val unknown = row.keys - setOf("id", "title", "url", "type", "action", "chars", "default", "viewName", "style")
            if (unknown.isNotEmpty()) throw SourceContentException(ContentError.InvalidRule, "$location.${unknown.first()}")
            // Legado's blank title/url/style rows only fill its grid. The host owns layout;
            // skip this exact inert shape before assigning IDs, preserving original error indices.
            // An unnamed target, control, action or viewName must still report its schema error.
            if (field == "exploreUrl" && layoutSeparator(row)) return@mapIndexedNotNull null
            val name = row.string("title")
            val type = row.string("type").ifBlank { "url" }
            if (type !in inputTypes + setOf("url", "button") || field == "exploreScreen" && type == "url")
                throw SourceContentException(ContentError.InvalidRule, "$location.type")
            val url = row.string("url")
            // Input IDs are form/infoMap keys; only URL/button rows may use explicit IDs or digests.
            // viewName is presentation-only, and host result routes carry drafts under these same keys.
            val key = if (type in inputTypes) name else row.string("id").ifBlank {
                val identity = "$type:$url:${row.string("action")}"; val occurrence = occurrences.merge(identity, 1, Int::plus)!!
                digest("$field:$identity:$occurrence")
            }
            if (name.isBlank() || name.length > 256 || key.isBlank() || key.length > 256)
                throw SourceContentException(ContentError.InvalidRule, "$location.title")
            val choices = (row["chars"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            if (type == "toggle" && choices.size != 2 || type == "select" && choices.isEmpty() || choices.size > 64 || choices.distinct().size != choices.size)
                throw SourceContentException(ContentError.InvalidRule, "$location.chars")
            val default = row.string("default").ifEmpty { choices.firstOrNull().orEmpty() }
            if (choices.isNotEmpty() && default !in choices) throw SourceContentException(ContentError.InvalidRule, "$location.default")
            RuleDiscoveryRow(key, name, type, url, row.string("action"), choices, default, location, row.string("viewName"))
        }
    }

    private fun layoutSeparator(row: JsonObject) = row.keys == separatorKeys && row["style"] is JsonObject &&
        listOf("title", "url").all { key ->
            (row[key] as? JsonPrimitive)?.let { it.isString && it.content.isBlank() } == true
        }

    private fun actions(context: RuleEvaluation): List<RuleDiscoveryAction> =
        ((context.discovery?.get("actions") as? JsonArray).orEmpty()).also {
            if (it.size > 16) throw SourceContentException(ContentError.Limit, "discovery.actions")
        }.mapIndexed { index, value ->
            val row = value.jsonObject; val kind = row.getValue("kind").jsonPrimitive.content
            val args = row.getValue("args").jsonArray
            fun text(i: Int) = args.getOrNull(i)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
            fun invalid(): Nothing = throw SourceContentException(ContentError.InvalidRule, "discovery.actions[$index].$kind")
            when (kind) {
                "refresh" -> { if (args.isNotEmpty()) invalid(); RuleDiscoveryAction("refresh") }
                "upConfig" -> {
                    if (args.isEmpty()) RuleDiscoveryAction("settings")
                    else {
                        if (args.size != 1) invalid()
                        val settings = stringMap(args[0]); validateValues(settings)
                        // Settings belong to this source. This protocol never mutates global reader preferences.
                        RuleDiscoveryAction("configuration", jsonValues(settings).toString())
                    }
                }
                "searchBook" -> {
                    if (args.size !in 1..2 || text(0).length > 1024 || args.size == 2 && text(1).isNotBlank() && text(1) != source.definition.importKey) invalid()
                    RuleDiscoveryAction("search", text(0))
                }
                "open" -> {
                    if (args.size !in 1..4 || text(3).isNotBlank() && text(3) != source.definition.importKey) invalid()
                    when (text(0)) {
                        "login" -> RuleDiscoveryAction("login")
                        "explore" -> RuleDiscoveryAction("results", text(1), text(2))
                        "search" -> RuleDiscoveryAction("search", text(2).ifBlank { text(1) })
                        else -> invalid()
                    }
                }
                "showBrowser" -> {
                    if (args.size !in 1..4) invalid()
                    val config = text(3).takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config != null && (config.keys - setOf("title")).isNotEmpty()) invalid()
                    RuleDiscoveryAction("browser", sourceLink(source.spec.baseUrl, text(0)), config?.string("title").orEmpty(), text(1).takeIf(String::isNotBlank), text(2))
                }
                else -> invalid()
            }
        }

    private fun readValues(context: RuleEvaluation) = stringMap(context.discovery!!.getValue("values"))
    private fun capture(context: RuleEvaluation, draft: Map<String, String> = readValues(context)) {
        validateValues(draft)
        val saving = context.discovery?.get("saveSeconds")?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
        if (saving != null) {
            if (saving !in 0..Int.MAX_VALUE.toLong()) throw SourceContentException(ContentError.InvalidRule, "infoMap.save")
            source.saveDiscoveryState("info", buildJsonObject {
                put("values", jsonValues(draft)); put("expires", if (saving == 0L) 0 else System.currentTimeMillis() + saving * 1000)
            }.toString())
        }
        actions(context).filter { it.kind == "configuration" }.forEach { source.saveDiscoveryState("configuration", it.value) }
        values = draft.toMap(); saveSeconds = saving
    }

    private fun stringMap(value: JsonElement): Map<String, String> = value.jsonObject.mapValues {
        val text = it.value as? JsonPrimitive ?: throw SourceContentException(ContentError.InvalidRule, "infoMap")
        if (!text.isString) throw SourceContentException(ContentError.InvalidRule, "infoMap")
        text.content
    }.also(::validateValues)
    private fun validateValues(value: Map<String, String>) {
        if (value.size > 128 || value.any { it.key.length > 256 || it.value.length > 4096 } || value.entries.sumOf { it.key.length + it.value.length } > 32768)
            throw SourceContentException(ContentError.Limit, "infoMap")
    }
    private fun jsonValues(value: Map<String, String>) = JsonObject(value.mapValues { JsonPrimitive(it.value) })
    companion object {
        // Catalogue rows are not infoMap entries. Larger real catalogues keep the existing
        // 128-value / 32,768-character form-state limits and 64-choice control limits.
        private const val MAX_CATALOG_ROWS = 1024
        private val separatorKeys = setOf("title", "url", "style")
        private val inputTypes = setOf("text", "toggle", "select")
    }
}
