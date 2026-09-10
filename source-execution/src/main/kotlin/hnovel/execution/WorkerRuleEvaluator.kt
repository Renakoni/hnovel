package hnovel.execution

import hnovel.rhino.*
import hnovel.rules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Typed output and invocation-local writes; the host decides whether to persist those writes. */
@Serializable data class ExecutedRule(val value: RuleValue, val writes: Map<String, String>,
    val bookWrites: Map<String, String?> = emptyMap(), val chapterWrites: Map<String, String?> = emptyMap())

/** Selectors, regex and scripts all run inside the worker's hard process deadline. */
internal object WorkerRuleEvaluator {
    fun evaluate(task: ExecutionTask.Rule, identity: ExecutionIdentity, limits: ExecutionLimits,
        bridge: HostBridge, library: ScriptLibrary?, archives: ArchiveDecoder = ArchiveDecoder.Zip): ExecutionResult {
        val context = RuleContext(identity.sourceId, task.bookId, task.chapterId, task.baseUrl,
            task.sourceVariables, task.bookVariables, task.chapterVariables)
        var scriptFailure: hnovel.rhino.FailureCode? = null
        val evaluator = RuleEvaluator { request, current, budget ->
            budget.check()
            val frame = ScriptFrame(identity.sourceId, identity.profile, task.bookId, task.chapterId,
                mapOf("result" to input(request.input)), task.key, task.page, task.baseUrl, current, task.input, budget, task.book, task.chapter)
            when (val result = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = limits.maxOutputBytes), archives)
                .evaluate(request.script, frame, library)) {
                is ScriptResult.Success -> value(Json.parseToJsonElement(result.json))
                is ScriptResult.Failure -> { scriptFailure = result.code; throw RuleScriptFailure(result.code.name) }
            }
        }
        val budget = RuleBudget(RuleLimits(timeoutMillis = limits.timeoutMillis, maxOutputChars = limits.maxOutputBytes))
        return when (val result = evaluator.evaluate(task.rule, task.input, context, task.output, task.location, budget)) {
            is RuleResult.Success -> {
                val json = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(result.value, context.writes(), context.bookWrites, context.chapterWrites))
                if (json.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
                else ExecutionResult.Success(json)
            }
            is RuleResult.Failure -> ExecutionResult.Failure(when (scriptFailure) {
                hnovel.rhino.FailureCode.Timeout -> FailureCode.Timeout
                hnovel.rhino.FailureCode.Cancelled -> FailureCode.Cancelled
                hnovel.rhino.FailureCode.Syntax -> FailureCode.ScriptSyntax
                hnovel.rhino.FailureCode.BridgeDenied -> FailureCode.BridgeDenied
                hnovel.rhino.FailureCode.ResultTooLarge -> FailureCode.OutputLimit
                null -> if (result.error.stage == RuleStage.Budget) FailureCode.Timeout else FailureCode.RuleRuntime
                else -> FailureCode.ScriptRuntime
            }, result.error)
        }
    }

    private fun input(value: RuleValue): JsonElement = when (value) {
        is RuleValue.Text -> JsonPrimitive(value.value)
        is RuleValue.Node -> if (value.kind == InputKind.Json) Json.parseToJsonElement(value.content) else JsonPrimitive(value.content)
        is RuleValue.Items -> JsonArray(value.values.map(::input))
        is RuleValue.Captures -> JsonArray(value.groups.map(::JsonPrimitive))
        RuleValue.Empty -> JsonNull
    }

    private fun value(json: JsonElement): RuleValue = when (json) {
        JsonNull -> RuleValue.Empty
        is JsonPrimitive -> RuleValue.Text(json.content)
        is JsonArray -> RuleValue.Items(json.map(::value))
        is JsonObject -> RuleValue.Node(json.toString(), InputKind.Json)
    }
}
