package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.OutputKind
import hnovel.rules.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ScriptDataBudgetTest {
    @Test fun nextPageRegexCanScanALargeDocumentAndReturnASmallUrlList() {
        val identity = ExecutionAuthority().issue("large-directory", "legado", "1")
        val rule = """@js:
            var n=result.match(/[^\d](\d+)页/);
            n=n?n[1]:'1';
            var list=[];
            for(var i=2;i<=n;i++){list.push(baseUrl.replace(/page=\d+/,'page=')+i);}
            list;
        """.trimIndent()
        WorkerRuntime().use { worker ->
            for (suffix in listOf("", "3页")) {
                val task = ExecutionTask.Rule(rule, RuleValue.Text("x".repeat(1_300_000) + suffix), OutputKind.UrlList,
                    baseUrl = "https://fixture.invalid/toc?page=1")
                val wire = ExecutionWire.encode(identity, task, ExecutionLimits(timeoutMillis = 30000, maxOutputBytes = 196608))
                val result = ExecutionWire.decodeResult(worker.executeSerialized(wire.toString(Charsets.UTF_8),
                    HostBridge { _, _ -> error("No host calls expected") }).toByteArray())
                assertTrue(result.toString(), result is ExecutionResult.Success)
                val expected = if (suffix.isEmpty()) emptyList() else listOf(2, 3).map { RuleValue.Text("https://fixture.invalid/toc?page=$it") }
                assertEquals(RuleValue.Items(expected), Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value)
            }
        }
    }

    @Test fun realHtmlInputCrossesTheChildPipeWithoutExpandingItsOutputAllowance() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("large-page", "legado", "1")
        val executor = IsolatedExecutor(authority = authority)
        val task = ExecutionTask.Rule("article@text", hnovel.rules.RuleValue.Text("<!--" + "x".repeat(9 * 1024 * 1024) + "--><article>chapter</article>"),
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

    @Test fun ruleScriptsReadLargeInputsWithoutExpandingResultsOrHostCalls() {
        val identity = ExecutionAuthority().issue("large-catalogue", "legado", "1")
        WorkerRuntime().use { worker ->
            fun evaluate(rule: String): ExecutionResult {
                val task = ExecutionTask.Rule(rule, RuleValue.Text("x".repeat(320000)), OutputKind.Text)
                val wire = ExecutionWire.encode(identity, task, ExecutionLimits(maxOutputBytes = 1024))
                return ExecutionWire.decodeResult(worker.executeSerialized(wire.toString(Charsets.UTF_8),
                    HostBridge { _, _ -> error("Oversized data must not reach the host") }).toByteArray())
            }
            for (rule in listOf("@js:result.length", "@js:java.getString('@js:result.length')")) {
                val result = evaluate(rule)
                assertTrue(result.toString(), result is ExecutionResult.Success)
                assertEquals(RuleValue.Text("320000"), Json.decodeFromString(ExecutedRule.serializer(),
                    (result as ExecutionResult.Success).output).value)
            }
            for (rule in listOf("@js:result", "@js:host.call('store',result)")) {
                assertEquals(FailureCode.OutputLimit, (evaluate(rule) as ExecutionResult.Failure).code)
            }
        }
    }
}
