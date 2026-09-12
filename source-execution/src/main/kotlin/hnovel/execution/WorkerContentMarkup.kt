package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json

/** Native conversion shares the worker deadline and wire envelope, but cannot call the host bridge. */
internal object WorkerContentMarkup {
    fun evaluate(task: ExecutionTask.ContentMarkup, limits: ExecutionLimits): ExecutionResult {
        val budget = RuleBudget(RuleLimits(timeoutMillis = limits.timeoutMillis, maxOutputChars = limits.maxOutputBytes))
        return when (val result = ContentMarkup.evaluate(task.html, task.location, budget)) {
            is RuleResult.Success -> {
                val json = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(result.value, emptyMap()))
                if (json.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit,
                    RuleError(RuleStage.Budget, task.location, "MarkupOutputLimit"))
                else ExecutionResult.Success(json)
            }
            is RuleResult.Failure -> ExecutionResult.Failure(when {
                Thread.currentThread().isInterrupted -> FailureCode.Cancelled
                result.error.code == "MarkupInputLimit" -> FailureCode.InputLimit
                result.error.code == "MarkupOutputLimit" -> FailureCode.OutputLimit
                result.error.stage == RuleStage.Budget -> FailureCode.Timeout
                else -> FailureCode.RuleRuntime
            }, result.error)
        }
    }
}
