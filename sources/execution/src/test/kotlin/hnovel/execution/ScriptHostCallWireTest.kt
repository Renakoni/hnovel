package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScriptHostCallWireTest {
    @Test fun childProcessPreservesOnlySafeMethodShapeAndTheActualRuleLocation() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("fixture", "legado", "1")
        val executor = IsolatedExecutor(authority = authority)
        val result = executor.execute(identity, ExecutionTask.Rule(
            "@js:host.call('cookie.getKey','PRIVATE_URL','PRIVATE_COOKIE')", RuleValue.Empty,
            location = RuleLocation("exploreUrl")), ExecutionLimits(timeoutMillis = 15000)) as ExecutionResult.Failure
        assertEquals(FailureCode.BridgeDenied, result.code)
        assertEquals(RuleLocation("exploreUrl", 4), result.ruleError!!.location)
        assertEquals(ScriptHostCall("cookie.getKey", 2, List(2) { ScriptArgumentType.String }), result.hostCall)
        assertFalse(Json.encodeToString(ExecutionResult.serializer(), result).contains("PRIVATE"))
        assertEquals(ExecutionResult.Success("42"), executor.execute(identity, ExecutionTask.Script("42")))
    }
}
