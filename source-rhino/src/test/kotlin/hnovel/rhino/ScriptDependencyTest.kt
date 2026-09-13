package hnovel.rhino

import hnovel.rules.ScriptDependency
import org.junit.Assert.*
import org.junit.Test

class ScriptDependencyTest {
    private val frame = ScriptFrame("fixture", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host call expected") })

    @Test fun missingInteropBindingsAreReportedWithoutIntroducingFakeJavaObjects() {
        for (dependency in ScriptDependency.entries) {
            val code = "${dependency.binding}();"
            val failure = engine.evaluate(code, frame) as ScriptResult.Failure
            assertEquals(FailureCode.UnsupportedDependency, failure.code)
            assertEquals(dependency, failure.dependency)
            assertFalse(failure.inLibrary)
            assertEquals(ScriptResult.Success("\"undefined\""), engine.evaluate("typeof ${dependency.binding}", frame))
            assertEquals(ScriptResult.Success("7"), engine.evaluate("try{$code}catch(e){7}", frame))
        }
    }

    @Test fun libraryErrorsIdentifyTheirOwnerAndOrdinaryErrorsDoNotLeakMessages() {
        ScriptLibrary(frame.sourceId, frame.profile, "new JavaImporter();").use { library ->
            val failure = engine.evaluate("42", frame, library) as ScriptResult.Failure
            assertEquals(ScriptDependency.JavaImporter, failure.dependency)
            assertTrue(failure.inLibrary)
        }
        for (code in listOf("missing_private_identifier", "throw new ReferenceError('JavaImporter synthetic-secret')",
            "var Packages={}; Packages.missing()")) {
            val failure = engine.evaluate(code, frame) as ScriptResult.Failure
            assertEquals(ScriptResult.Failure(FailureCode.Runtime, "script failed"), failure)
            assertFalse(failure.toString().contains("synthetic-secret"))
        }
    }

    @Test fun sourcePreludeIsReadOnlyInvocationDataAndNeverRunsImplicitly() {
        val first = frame.copy(sourceLoginUrl = "var marker='first';")
        assertEquals(ScriptResult.Success("\"undefined\""), engine.evaluate("typeof marker", first))
        assertEquals(ScriptResult.Success("\"first\""), engine.evaluate(
            "source.loginUrl='changed';delete source.loginUrl;eval(source.getLoginUrl());marker", first))
        ScriptLibrary(frame.sourceId, frame.profile, "function prelude(){return this.source.loginUrl}").use { library ->
            assertEquals(ScriptResult.Success("\"var marker='first';\""), engine.evaluate("prelude.call(this)", first, library))
            assertEquals(ScriptResult.Success("\"second\""), engine.evaluate("prelude.call(this)", first.copy(sourceLoginUrl = "second"), library))
        }
        ScriptLibrary(frame.sourceId, frame.profile, "source.loginUrl").use { library ->
            assertEquals(FailureCode.Runtime, (engine.evaluate("42", first, library) as ScriptResult.Failure).code)
        }
    }
}
