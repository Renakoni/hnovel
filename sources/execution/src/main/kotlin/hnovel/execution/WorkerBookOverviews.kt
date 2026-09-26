package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.serialization.json.Json

/** Only fixed selectors run here; scripts and their shared state retain the ordinary rule path. */
internal object WorkerBookOverviews {
    fun evaluate(task: ExecutionTask.BookOverviews, identity: ExecutionIdentity, limits: ExecutionLimits): ExecutionResult {
        if (task.inputs.size !in 1..ExecutionTask.BookOverviews.MAX_ROWS ||
            !ExecutionTask.BookOverviews.supports(task.nameRule) || !ExecutionTask.BookOverviews.supports(task.urlRule))
            return ExecutionResult.Failure(FailureCode.InvalidTask)
        val started = System.nanoTime()
        val values = mutableListOf<RuleValue>()
        for (input in task.inputs) {
            fun field(rule: String, name: String, output: OutputKind): ExecutionResult {
                val remaining = limits.timeoutMillis - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) return ExecutionResult.Failure(FailureCode.Timeout)
                return WorkerRuleEvaluator.evaluate(ExecutionTask.Rule(rule, input, output,
                    RuleLocation("${task.field}.$name"), baseUrl = task.baseUrl), identity,
                    limits.copy(timeoutMillis = remaining, maxOutputBytes = minOf(limits.maxOutputBytes, 196608)),
                    HostBridge.None, null)
            }
            var title = task.fallbackTitle
            if (task.nameRule.isNotBlank()) {
                val result = field(task.nameRule, "name", OutputKind.Text)
                if (result is ExecutionResult.Failure) return result
                title = text(result as ExecutionResult.Success).ifBlank { task.fallbackTitle }
            }
            var url = ""
            // Empty titles must not evaluate a URL rule which the ordinary list path skips.
            if (title.isNotBlank() && task.urlRule.isNotBlank()) {
                val result = field(task.urlRule, "bookUrl", OutputKind.Url)
                if (result is ExecutionResult.Failure) return result
                url = text(result as ExecutionResult.Success)
            }
            values += RuleValue.Items(listOf(RuleValue.Text(title), RuleValue.Text(url)))
        }
        val encoded = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(RuleValue.Items(values), emptyMap()))
        return if (encoded.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
            else ExecutionResult.Success(encoded)
    }

    private fun text(result: ExecutionResult.Success): String =
        when (val value = Json.decodeFromString(ExecutedRule.serializer(), result.output).value) {
            is RuleValue.Text -> value.value
            RuleValue.Empty -> ""
            else -> error("Expected a scalar overview field")
        }
}
