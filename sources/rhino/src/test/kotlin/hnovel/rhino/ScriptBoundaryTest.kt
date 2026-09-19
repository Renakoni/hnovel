package hnovel.rhino

import org.junit.Assert.*
import org.junit.Test

class ScriptBoundaryTest {
    private val frame = ScriptFrame("source-a", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("denied") },
        ScriptLimits(instructionLimit = 1_000_000, maxInterpreterStackDepth = 32))

    @Test fun recursionIsBoundedBeforeTheInstructionBudgetAndContextRecovers() {
        val recursion = "function recurse(n){return 1+recurse(n+1);} recurse(0)"
        assertEquals(FailureCode.Runtime, (engine.evaluate(recursion, frame) as ScriptResult.Failure).code)
        ScriptLibrary("source-a", "legado", recursion).use { library ->
            assertEquals(FailureCode.Runtime, (engine.evaluate("1", frame, library) as ScriptResult.Failure).code)
        }
        assertEquals(ScriptResult.Success("42"), engine.evaluate("21*2", frame))
    }

    @Test fun reflectionClassloadingFilesSocketsAndProcessesHaveNoJavaEntryPoint() {
        val attempts = listOf(
            "Packages.java.lang.System.exit(0)", "java.lang.Runtime.getRuntime().exec('unused')",
            "Java.type('java.lang.Class')", "getClass(java)", "java.getClass().getClassLoader()",
            "book.getClass().forName('java.lang.Runtime')", "new java.io.File('/unused')",
            "new Packages.java.net.Socket('127.0.0.1',1)", "new JavaAdapter(java.lang.Runnable,{run:function(){}})",
            "java.ajax.constructor('return Packages.java.lang.System')()"
        )
        for (script in attempts) {
            assertTrue(script, engine.evaluate(script, frame) is ScriptResult.Failure)
        }
        assertEquals(ScriptResult.Success("42"), engine.evaluate("21*2", frame))
    }
}
