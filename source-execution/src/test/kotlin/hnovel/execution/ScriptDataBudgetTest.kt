package hnovel.execution

import hnovel.rhino.HostBridge
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ScriptDataBudgetTest {
    @Test fun realHtmlInputCrossesTheChildPipeWithoutExpandingItsOutputAllowance() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("large-page", "legado", "1")
        val executor = IsolatedExecutor(authority = authority)
        val task = ExecutionTask.Rule("article@text", hnovel.rules.RuleValue.Text("<!--" + "x".repeat(320000) + "--><article>chapter</article>"),
            hnovel.rules.OutputKind.Text)
        val result = executor.execute(identity, task)
        assertTrue(result.toString(), result is ExecutionResult.Success)
        assertEquals(hnovel.rules.RuleValue.Text("chapter"), kotlinx.serialization.json.Json.decodeFromString(ExecutedRule.serializer(),
            (result as ExecutionResult.Success).output).value)
        assertEquals(ExecutionResult.Failure(FailureCode.InputLimit), executor.execute(identity,
            task.copy(input = hnovel.rules.RuleValue.Text("x".repeat(ExecutionWire.MAX_INPUT_BYTES)))))
    }

    @Test fun largerHostBudgetSupportsRealPayloadsButCannotRemoveTheDataCeiling() {
        val identity = ExecutionAuthority().issue("known-source", "legado", "1")
        WorkerRuntime().use { worker ->
            fun evaluate(size: Int, outputBudget: Int): ExecutionResult {
                val task = ExecutionTask.Script("result.length", JsonPrimitive("x".repeat(size)))
                val wire = ExecutionWire.encode(identity, task, ExecutionLimits(maxOutputBytes = outputBudget))
                return ExecutionWire.decodeResult(worker.executeSerialized(wire.toString(Charsets.UTF_8),
                    HostBridge { _, _ -> error("No host calls expected") }).toByteArray())
            }
            assertEquals(ExecutionResult.Success("60000"), evaluate(60000, 1024))
            assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), evaluate(70000, 65536))
            assertEquals(ExecutionResult.Success("70000"), evaluate(70000, 196608))
            assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), evaluate(196608, 4 * 1024 * 1024))
        }
    }
}
