package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.net.URL

/** Evaluate request scripts only in the worker. The host still compiles and authorizes every URL. */
internal class ScriptRequestTemplates(private val scope: Scriptable, private val frame: ScriptFrame) {
    private val parser = RuleParser()
    private var depth = 0
    private var resolvingHeaders = false

    /** Local URL preparation stays pure; only an actual broker operation requests source headers. */
    fun call(cx: Context, bridge: HostBridge, name: String, args: List<JsonElement>): JsonElement {
        if (name != "java.startBrowserAwait") return dispatch(cx, bridge, name, args)
        require(args.size in 2..3)
        val refetch = args.getOrNull(2)?.jsonPrimitive?.boolean ?: true
        val page = dispatch(cx, bridge, name, args.take(2))
        // Verification can change authentication; evaluate the retry header only after it finishes.
        return if (refetch) dispatch(cx, bridge, "browser.refetch", args.take(1)) else page
    }

    private fun dispatch(cx: Context, bridge: HostBridge, name: String, args: List<JsonElement>): JsonElement {
        val prepared = prepare(cx, name, args)
        val inherits = name in setOf("java.ajax", "java.ajaxAll", "java.connect", "java.cacheFile", "java.downloadFile", "java.importScript",
            "java.webView", "java.webViewGetSource", "java.webViewGetOverrideUrl", "java.startBrowser", "java.startBrowserAwait", "browser.refetch") &&
            !(name == "java.connect" && args.getOrNull(1)?.let { it != JsonNull } == true) &&
            !(name == "java.ajaxAll" && prepared[0].jsonArray.isEmpty()) &&
            !(name == "java.importScript" && !prepared[0].jsonPrimitive.content.startsWith("http", true)) &&
            !(name == "java.downloadFile" && args.size == 2)
        if (!inherits || resolvingHeaders || frame.sourceHeaderRule.isBlank()) return bridge.call(name, prepared)
        val arguments = listOf(JsonPrimitive(name), JsonArray(prepared), headers(cx))
        if (JsonArray(arguments).toString().length > cx.getThreadLocal(bridgeLimitKey) as Int) throw ResultTooLarge()
        return bridge.call("request.withHeaders", arguments)
    }

    private fun headers(cx: Context): JsonObject {
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val rule = frame.sourceHeaderRule.trim()
        if (rule.length > limit) throw ResultTooLarge()
        resolvingHeaders = true
        try {
            val text = if (rule.startsWith('{')) rule else {
                val code = when {
                    rule.startsWith("@js:", true) -> rule.substring(4)
                    rule.startsWith("<js>", true) && rule.endsWith("</js>", true) -> rule.substring(4, rule.length - 5)
                    else -> rule
                }
                // Header locals and `result` must not replace the caller's parsing state.
                val headerScope = NativeObject().apply { prototype = scope; put("result", this, null) }
                val value = evaluateGlobal(cx, headerScope, code, "source-header")
                val result = Json.parseToJsonElement(BoundedJsonResult(limit).encode(value))
                if (result is JsonPrimitive) result.content else result.toString()
            }
            if (text.length > limit) throw ResultTooLarge()
            return JsonObject(Json.parseToJsonElement(text).jsonObject.mapValues { JsonPrimitive(it.value.jsonPrimitive.content) })
        } finally { resolvingHeaders = false }
    }

    fun prepare(cx: Context, name: String, args: List<JsonElement>): List<JsonElement> {
        if (name !in setOf("java.ajax", "java.ajaxAll", "java.connect", "java.cacheFile", "java.downloadFile", "java.importScript")) return args
        require(args.isNotEmpty())
        if (name == "java.importScript" && !args[0].jsonPrimitive.content.startsWith("http", true)) return args
        if (++depth > 32) { depth--; throw ScriptBudgetExceeded() }
        try {
            val limit = cx.getThreadLocal(bridgeLimitKey) as Int
            var used = 2L
            fun item(value: JsonElement): JsonPrimitive = JsonPrimitive(expand(cx, value.jsonPrimitive.content)).also {
                used += it.toString().length + 1L
                if (used > limit) throw ResultTooLarge()
            }
            val urlIndex = if (name == "java.downloadFile" && args.size == 2) 1 else 0
            val first = args[urlIndex]
            val expanded = if (name == "java.ajaxAll") JsonArray(first.jsonArray.map(::item))
                else item((first as? JsonArray)?.firstOrNull() ?: first)
            return args.toMutableList().apply { this[urlIndex] = expanded }.also {
                if (JsonArray(it).toString().length > limit) throw ResultTooLarge()
            }
        } finally { depth-- }
    }

    private fun expand(cx: Context, original: String): String {
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val budget = RuleBudget(RuleLimits(maxRuleChars=limit, maxOutputChars=limit))
        val at = RuleLocation("request")
        fun bounded(text: String): String { if (text.length > limit) throw ResultTooLarge(); return text }
        fun evaluate(code: String, input: String? = null, base: String? = null, emptyNull: Boolean = false): String {
            bounded(code)
            val oldResult = scope.get("result", scope)
            val oldBase = scope.get("baseUrl", scope)
            try {
                scope.put("result", scope, input)
                if (base != null) scope.put("baseUrl", scope, base)
                val result = evaluateGlobal(cx, scope, code, "request-script")
                if (!Undefined.isUndefined(result)) BoundedJsonResult(limit).encode(result)
                return bounded(if (emptyNull && (result == null || Undefined.isUndefined(result))) "" else Context.toString(result))
            } finally {
                scope.put("result", scope, oldResult)
                scope.put("baseUrl", scope, oldBase)
            }
        }
        try {
            var value = original
            val steps = if (original.contains("@js:", true) || original.contains("<js>", true)) parser.parse(original, at, budget).steps else emptyList()
            for (step in steps) {
                value = if (step.script) evaluate(step.text, value)
                    else step.text.trim().takeIf(String::isNotEmpty)?.replace("@result", value)?.let(::bounded) ?: value
            }
            val text = value
            var index = 0
            val expanded = StringBuilder()
            while (index < text.length) {
                budget.check()
                if (text.startsWith("{{", index)) {
                    val end = parser.balancedEnd(text, index, at, budget)
                    require(text.substring(end - 2, end) == "}}")
                    val expression = text.substring(index + 2, end - 2)
                    // Keep existing static compiler's charset-aware key/body/header expansion.
                    expanded.append(if (expression.trim() in setOf("key", "page", "baseUrl")) text.substring(index, end)
                        else evaluate(expression, emptyNull=true))
                    index = end
                } else expanded.append(text[index++])
                if (expanded.length > limit) throw ResultTooLarge()
            }
            value = expanded.toString()
            val optionStart = Regex(",\\s*(?=\\{)").find(value) ?: return value
            val options = Json.parseToJsonElement(value.substring(optionStart.range.last + 1)).jsonObject
            val script = options["js"]?.jsonPrimitive?.content ?: return value
            // URL-option JS receives the resolved URL, and baseUrl follows its authority.
            val raw = value.substring(0, optionStart.range.first).trim()
                .replace(Regex("\\{\\{\\s*key\\s*\\}\\}")) { frame.key }
                .replace(Regex("\\{\\{\\s*page\\s*\\}\\}")) { frame.page.toString() }
                .replace(Regex("\\{\\{\\s*baseUrl\\s*\\}\\}")) { frame.baseUrl }
                .replace(Regex("<([^<>]+)>")) { match -> match.groupValues[1].split(',').let { it[(frame.page - 1).coerceIn(0, it.lastIndex)].trim() } }
            val url = if (frame.baseUrl.isEmpty()) URL(raw) else URL(URL(frame.baseUrl), raw)
            val resolved = evaluate(script, url.toString(), "${url.protocol}://${url.authority}")
            return bounded(resolved + "," + JsonObject(options - "js"))
        } catch (_: RuleBudgetExceeded) { throw ScriptBudgetExceeded() }
    }
}
