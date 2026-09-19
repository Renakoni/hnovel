package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RequestOptionsWireTest {
    @Test fun requestParseFailureSurvivesScriptAndRuleWireWithoutLosingItsField() {
        val identity = ExecutionAuthority().issue("source", "legado", "revision")
        val code = "java.ajax(${JsonPrimitive("/search,{'body':'private-unterminated}")})"
        var calls = 0
        WorkerRuntime().use { worker ->
            for (task in listOf(ExecutionTask.Script(code),
                ExecutionTask.Rule("@js:$code", RuleValue.Empty, location = RuleLocation("searchUrl")))) {
                val wire = ExecutionWire.encode(identity, task, ExecutionLimits())
                val result = ExecutionWire.decodeResult(worker.executeSerialized(wire.toString(Charsets.UTF_8),
                    HostBridge { _, _ -> calls++; JsonPrimitive("unexpected") }).toByteArray()) as ExecutionResult.Failure
                assertEquals(FailureCode.RequestSyntax, result.code)
                if (task is ExecutionTask.Rule) {
                    assertEquals(RuleStage.Parse, result.ruleError?.stage)
                    assertEquals("searchUrl", result.ruleError?.location?.field)
                    assertEquals("RequestSyntax", result.ruleError?.code)
                }
                assertFalse(result.toString().contains("private"))
            }
        }
        assertEquals(0, calls)
    }
}
