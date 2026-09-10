package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.jsoup.parser.Parser

/** Nested AnalyzeRule helpers share the current Rhino context, variables and rule budget. */
internal class ScriptRuleHelpers(private val scope: Scriptable, frame: ScriptFrame, private val limits: ScriptLimits) {
    private val context = frame.ruleContext ?: RuleContext(frame.sourceId, frame.bookId, frame.chapterId, frame.baseUrl)
    private val root = frame.ruleInput ?: value(frame.variables["result"] ?: JsonNull)
    private val budget = frame.ruleBudget ?: RuleBudget(RuleLimits(maxInputChars = limits.maxBridgeChars,
        maxOutputChars = limits.maxBridgeChars))
    private var depth = 0
    private var elements: RuleValue? = null

    fun elementView(cx: Context, active: Scriptable, data: JsonElement): Any? {
        fun convert(value: RuleValue): Any? = when (value) {
            is RuleValue.Node -> if (value.kind == InputKind.Html || value.kind == InputKind.Xml)
                ScriptDom.fragment(cx, active, value.content, context.baseUrl, value.kind == InputKind.Xml)
                else JsonScriptData(cx, active, limits.maxBridgeChars).convert(json(value))
            is RuleValue.Items -> {
                val nodes = value.values.map(::convert)
                if (nodes.isNotEmpty() && nodes.all { it is ScriptDomElement }) ScriptDom.elements(cx, active, nodes.map { (it as ScriptDomElement).element })
                else ScriptRealm.current(cx).arrayIn(active, nodes.toTypedArray())
            }
            else -> JsonScriptData(cx, active, limits.maxBridgeChars).convert(json(value))
        }
        return elements?.let(::convert) ?: JsonScriptData(cx, active, limits.maxBridgeChars).convert(data)
    }

    fun supports(name: String, args: List<JsonElement>) = name in setOf("java.getString", "java.getStringList",
        "java.getElement", "java.getElements", "java.put") || name == "java.get" && args.size == 1

    fun call(cx: Context, name: String, args: List<JsonElement>): JsonElement {
        elements = null
        if (++depth > budget.limits.maxDepth) { depth--; throw ScriptBudgetExceeded() }
        try {
            budget.check()
            if (name == "java.get") return JsonPrimitive(context.get(args.single().jsonPrimitive.content))
            if (name == "java.put") {
                require(args.size == 2)
                return JsonPrimitive(context.put(args[0].jsonPrimitive.content, args[1].jsonPrimitive.content))
            }
            val strings = name == "java.getString" || name == "java.getStringList"
            require(args.size in 1..if (strings) 3 else 1)
            val rule = args[0].let { if (it == JsonNull && strings) "" else it.jsonPrimitive.content }
            if (rule.isEmpty()) return when (name) {
                "java.getString" -> JsonPrimitive("")
                "java.getElements" -> JsonArray(emptyList())
                else -> JsonNull
            }
            val booleanOverload = name == "java.getString" && args.size == 2 && args[1] is JsonPrimitive && args[1].jsonPrimitive.booleanOrNull != null
            val unescape = !booleanOverload || args[1].jsonPrimitive.boolean
            val input = args.getOrNull(1)?.takeUnless { booleanOverload || it == JsonNull }?.let(::value) ?: root
            val url = args.getOrNull(2)?.jsonPrimitive?.boolean ?: false
            val output = when (name) {
                "java.getString" -> if (url) OutputKind.Url else OutputKind.Text
                "java.getStringList" -> if (url) OutputKind.UrlList else OutputKind.TextList
                "java.getElement" -> OutputKind.Element
                else -> OutputKind.Elements
            }
            val evaluator = RuleEvaluator(unescapeHtml = false) { request, _, _ ->
                budget.checkSize(request.script.length, limits.maxScriptChars)
                val old = scope.get("result", scope)
                try {
                    scope.put("result", scope, JsonScriptData(cx, scope, limits.maxBridgeChars).convert(json(request.input)))
                    value(Json.parseToJsonElement(BoundedJsonResult(limits.maxBridgeChars)
                        .encode(evaluateGlobal(cx, scope, request.script, "nested-rule"))))
                } finally { scope.put("result", scope, old) }
            }
            val result = evaluator.evaluate(rule, input, context, output, RuleLocation(name), budget)
            if (result is RuleResult.Failure) {
                if (result.error.stage == RuleStage.Budget) throw ScriptBudgetExceeded()
                error("Nested rule failed")
            }
            val selected = (result as RuleResult.Success).value
            if (name == "java.getElement" || name == "java.getElements") {
                elements = if (name == "java.getElement" && selected is RuleValue.Items && selected.values.singleOrNull().let { it is RuleValue.Node && it.kind == InputKind.Json }) selected.values.single() else selected
            }
            return when (name) {
                "java.getString" -> JsonPrimitive(text(selected).let { if (unescape) Parser.unescapeEntities(it, false) else it })
                "java.getStringList" -> JsonArray((if (selected is RuleValue.Text) selected.value.split('\n') else items(selected).map(::text)).map(::JsonPrimitive))
                // JSON getObject returns one structured value; HTML/XPath getElement returns a node list.
                "java.getElement" -> if (selected is RuleValue.Items && selected.values.singleOrNull().let { it is RuleValue.Node && it.kind == InputKind.Json })
                    json(selected.values.single()) else json(selected)
                else -> json(selected)
            }
        } catch (_: RuleBudgetExceeded) { throw ScriptBudgetExceeded() }
        finally { depth-- }
    }

    private fun items(value: RuleValue): List<RuleValue> = when (value) {
        RuleValue.Empty -> emptyList()
        is RuleValue.Items -> value.values
        else -> listOf(value)
    }
    private fun text(value: RuleValue): String = when (value) {
        RuleValue.Empty -> ""
        is RuleValue.Text -> value.value
        is RuleValue.Node -> value.content
        is RuleValue.Items -> value.values.joinToString("\n", transform = ::text)
        is RuleValue.Captures -> value.groups.joinToString("\n")
    }
    private fun json(value: RuleValue): JsonElement = when (value) {
        RuleValue.Empty -> JsonNull
        is RuleValue.Text -> JsonPrimitive(value.value)
        is RuleValue.Node -> if (value.kind == InputKind.Json) Json.parseToJsonElement(value.content) else JsonPrimitive(value.content)
        is RuleValue.Items -> JsonArray(value.values.map(::json))
        is RuleValue.Captures -> JsonArray(value.groups.map(::JsonPrimitive))
    }
    private fun value(json: JsonElement): RuleValue = when (json) {
        JsonNull -> RuleValue.Empty
        is JsonPrimitive -> RuleValue.Text(json.content)
        is JsonArray -> RuleValue.Items(json.map(::value))
        is JsonObject -> RuleValue.Node(json.toString(), InputKind.Json)
    }
}
