package hnovel.rhino

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.NativeJSON
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ScriptLibraryTest {
    private val frame = ScriptFrame("source-a", "legado", bookId = "book-a", page = 1)
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No library host capability") })
    private val code = "var state={n:0}; function next(){return ++state.n;}"

    private fun output(script: String, library: ScriptLibrary, current: ScriptFrame = frame): String =
        (engine.evaluate(script, current, library) as ScriptResult.Success).json

    @Test fun sharedObjectsAndClosuresPersistWhileInvocationBindingsAreFresh() {
        ScriptLibrary(frame.sourceId, frame.profile, code).use { library ->
            assertEquals("[1,\"book-a\",1]", output("var local='only-a'; [next(),book.id,page]", library))
            assertEquals("[2,\"book-b\",2,\"undefined\"]", output("[next(),book.id,page,typeof local]",
                library, frame.copy(bookId = "book-b", page = 2)))
        }
        ScriptLibrary(frame.sourceId, frame.profile, code).use { fresh -> assertEquals("1", output("next()", fresh)) }
    }

    @Test fun scopeLookupAndShadowingMatchPinnedSharedJsScopeConstruction() {
        // Reference SharedJsScope/getRuntimeScope: empty NativeObject over standard objects,
        // evaluate the library, seal its root, then use it as fresh bindings' prototype.
        val cx = Context.enter()
        val expected = try {
            cx.languageVersion = Context.VERSION_ES6
            val shared = NativeObject().apply { prototype = cx.initStandardObjects() }
            cx.evaluateString(shared, code, "reference-library", 1, null)
            shared.sealObject()
            listOf("[next(), typeof nonexistent]", "var state={n:100}; [next(),state.n]", "[next(),state.n]").map { script ->
                val bindings = NativeObject().apply { prototype = shared }
                val result = cx.evaluateString(bindings, script, "reference", 1, null)
                NativeJSON.stringify(cx, bindings, result, null, null).toString()
            }
        } finally { Context.exit() }
        ScriptLibrary(frame.sourceId, frame.profile, code).use { library ->
            val scripts = listOf("[next(), typeof nonexistent]", "var state={n:100}; [next(),state.n]", "[next(),state.n]")
            assertEquals(expected, scripts.map { output(it, library) })
        }
    }

    @Test fun equalLibraryTextDoesNotShareSourcesOrProfilesAndClosedStateIsRejected() {
        val library = ScriptLibrary(frame.sourceId, frame.profile, code)
        assertEquals("1", output("next()", library))
        assertEquals(FailureCode.BridgeDenied, (engine.evaluate("next()", frame.copy(sourceId = "source-b"), library) as ScriptResult.Failure).code)
        assertEquals(FailureCode.BridgeDenied, (engine.evaluate("next()", frame.copy(profile = "other"), library) as ScriptResult.Failure).code)
        ScriptLibrary("source-b", frame.profile, code).use { other ->
            assertEquals("1", output("next()", other, frame.copy(sourceId = "source-b")))
        }
        library.close()
        assertEquals(FailureCode.BridgeDenied, (engine.evaluate("next()", frame, library) as ScriptResult.Failure).code)
    }

    @Test fun sharedLibraryCallsSerializeAcrossConcurrentBooks() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            ScriptLibrary(frame.sourceId, frame.profile, code).use { library ->
                val calls = (1..40).map { book -> Callable { output("next()", library, frame.copy(bookId = "$book")).toInt() } }
                assertEquals((1..40).toList(), pool.invokeAll(calls).map { it.get() }.sorted())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun libraryInitializationIsBudgetedAndCannotObtainInvocationBindings() {
        ScriptLibrary(frame.sourceId, frame.profile, "java.ajax('https://fixture.invalid')").use { library ->
            assertEquals(FailureCode.Runtime, (engine.evaluate("1", frame, library) as ScriptResult.Failure).code)
            assertNull(library.scope)
        }
        ScriptLibrary(frame.sourceId, frame.profile, "while(true){}").use { library ->
            assertEquals(FailureCode.Timeout, (engine.evaluate("1", frame, library) as ScriptResult.Failure).code)
            assertNull(library.scope)
        }
        ScriptLibrary(frame.sourceId, frame.profile, "var x='${"x".repeat(100)}';").use { library ->
            val small = RhinoScriptEngine(HostBridge { _, _ -> JsonNull }, ScriptLimits(maxScriptChars = 64))
            assertEquals(FailureCode.ResultTooLarge, (small.evaluate("1", frame, library) as ScriptResult.Failure).code)
        }
        assertNull(Context.getCurrentContext())
    }

    @Test fun storedPureMethodUsesTheCallingContextAndStillHasNoJavaWrapper() {
        ScriptLibrary(frame.sourceId, frame.profile, "var holder={};").use { library ->
            assertEquals("null", output("holder.encode=java.base64Encode; undefined", library))
            assertEquals("[\"YQ==\",\"undefined\"]", output("[holder.encode('a'),typeof holder.encode.getClass]", library))
            val smaller = RhinoScriptEngine(HostBridge { _, _ -> JsonNull }, ScriptLimits(maxBridgeChars = 128))
            assertEquals(FailureCode.ResultTooLarge,
                (smaller.evaluate("holder.encode('a'.repeat(100))", frame, library) as ScriptResult.Failure).code)
        }
    }
}
