package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkerContentMarkupTest {
    private val identity = ExecutionAuthority().issue("markup", "legado", "1")
    private fun run(task: ExecutionTask, limits: ExecutionLimits = ExecutionLimits()): ExecutionResult =
        ExecutionWire.decodeResult(WorkerMain.executeSerialized(
            ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
            HostBridge { _, _ -> throw AssertionError("Markup must not call the host") }
        ).toByteArray(Charsets.UTF_8))

    @Test fun realChildConvertsANormalChapterWithDefaultLimitsAndNoWrites() {
        val result = IsolatedExecutor().execute(identity,
            ExecutionTask.ContentMarkup("<p>${"a".repeat(80)}</p>".repeat(80)))
        assertTrue(result.toString(), result is ExecutionResult.Success)
        val executed = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
        assertEquals(80, (executed.value as RuleValue.Items).values.size)
        assertEquals(ExecutedRule(executed.value, emptyMap()), executed)
    }

    @Test fun byteLimitIncludesMultibyteTextAndTheWireEnvelope() {
        val failure = run(ExecutionTask.ContentMarkup("<p>${"中".repeat(200)}</p>"),
            ExecutionLimits(maxOutputBytes = 512)) as ExecutionResult.Failure
        assertEquals(FailureCode.OutputLimit, failure.code)
        assertEquals(RuleError(RuleStage.Budget, RuleLocation("ruleContent.parts"), "MarkupOutputLimit"), failure.ruleError)
    }

    @Test fun markupCannotExecuteScriptsAndUntrustedScriptsKeepTheirInstructionBudget() {
        assertTrue(run(ExecutionTask.ContentMarkup("<p>safe</p><script>java.ajax('/never')</script>")) is ExecutionResult.Success)
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), run(ExecutionTask.Script("while(true){}")))
        assertEquals(ExecutionResult.Success("42"), run(ExecutionTask.Script("21*2")))
    }

    @Test fun limitsAndCancellationKeepTheirLocationsAcrossTheWire() {
        val task = ExecutionTask.ContentMarkup("<div>".repeat(70), RuleLocation("ruleContent.parts", 9))
        val failure = run(task) as ExecutionResult.Failure
        assertEquals(FailureCode.Timeout, failure.code)
        assertEquals(RuleError(RuleStage.Budget, task.location, "MarkupDepthLimit"), failure.ruleError)
        Thread.currentThread().interrupt()
        try { assertEquals(FailureCode.Cancelled, (run(task.copy(html = "<p>normal</p>")) as ExecutionResult.Failure).code) }
        finally { Thread.interrupted() }
    }
}
