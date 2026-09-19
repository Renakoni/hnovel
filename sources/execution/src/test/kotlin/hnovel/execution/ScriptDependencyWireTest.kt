package hnovel.execution

import hnovel.rules.*
import org.junit.Assert.*
import org.junit.Test

class ScriptDependencyWireTest {
    @Test fun childProcessKeepsDependencyAndLibraryLocationAcrossTheWire() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("fixture", "legado", "1")
        val executor = IsolatedExecutor(authority = authority)
        val result = executor.execute(identity, ExecutionTask.Rule("@js:42", RuleValue.Empty,
            location = RuleLocation("exploreUrl"), libraryCode = "new JavaImporter()"), ExecutionLimits(timeoutMillis = 15000))
        val failure = result as ExecutionResult.Failure
        assertEquals(FailureCode.UnsupportedDependency, failure.code)
        assertEquals(ScriptDependency.JavaImporter, failure.dependency)
        assertEquals(RuleLocation("jsLib"), failure.ruleError!!.location)
        assertEquals(ExecutionResult.Success("\"fixture-prelude\""), executor.execute(identity,
            ExecutionTask.Script("source.getLoginUrl()", sourceLoginUrl = "fixture-prelude")))
    }
}
