package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context

class RhinoBridgeTest {
    private val frame = ScriptFrame("source-a", "legado", key = "query", page = 3, baseUrl = "https://fixture.invalid/")

    @Test fun bookAndChapterHaveNormalObjectPrototypesWithAndWithoutALibrary() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> JsonNull })
        val script = "[book.hasOwnProperty('id'),chapter.hasOwnProperty('id'),book.toString(),chapter.constructor===Object,Object.getPrototypeOf(book)===Object.prototype]"
        ScriptLibrary(frame.sourceId, frame.profile, "var state={};").use { library ->
            for (scope in listOf(null, library)) {
                assertEquals("[true,true,\"[object Object]\",true,true]", (engine.evaluate(script,
                    frame.copy(bookId = "book", chapterId = "chapter"), scope) as ScriptResult.Success).json)
                assertEquals("[false,false,\"[object Object]\",true,true]", (engine.evaluate(script, frame, scope) as ScriptResult.Success).json)
            }
        }
    }

    @Test fun interruptedBridgeFailureRemainsCancellationEvenWhenScriptCatchesErrors() {
        val engine = RhinoScriptEngine(HostBridge { _, _ ->
            Thread.currentThread().interrupt()
            throw java.io.IOException("interrupted host request")
        })
        try {
            val result = engine.evaluate("try{java.ajax(baseUrl)}catch(e){'recovered'}", frame)
            assertEquals(FailureCode.Cancelled, (result as ScriptResult.Failure).code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertNull(Context.getCurrentContext())
        assertEquals("42", (engine.evaluate("21*2", frame) as ScriptResult.Success).json)
    }

    @Test fun nestedArgumentsAndResultsKeepTheirJsTypes() {
        val engine = RhinoScriptEngine(HostBridge { name, args ->
            assertEquals("echo", name)
            assertEquals(Json.parseToJsonElement("[7,true,null,[1,2],{\"nested\":\"ok\"}]"), JsonArray(args))
            JsonArray(args)
        })
        val result = engine.evaluate("var x=host.call('echo',7,true,null,[1,2],{nested:'ok'}); [x[0]+1,x[1],x[2],x[3].map(v=>v*2),x[4].nested,typeof x.getClass]", frame)
        assertEquals("[8,true,null,[2,4],\"ok\",\"undefined\"]", (result as ScriptResult.Success).json)
    }

    @Test fun sourceCacheAndAjaxUseTheSameBoundSynchronousDataPort() {
        val values = mutableMapOf<String, JsonElement>()
        val calls = mutableListOf<String>()
        val engine = RhinoScriptEngine(HostBridge { name, args ->
            calls += name
            when (name) {
                "cache.put" -> { values[args[0].jsonPrimitive.content] = args[1]; JsonNull }
                "cache.get" -> values[args[0].jsonPrimitive.content] ?: JsonNull
                "java.ajax" -> JsonPrimitive("fixture body")
                else -> error("Unknown API")
            }
        })
        val result = engine.evaluate("cache.put('reply',java.ajax(baseUrl)); [cache.get('reply'),source.getKey()]", frame)
        assertEquals("[\"fixture body\",\"source-a\"]", (result as ScriptResult.Success).json)
        assertEquals(listOf("java.ajax", "cache.put", "cache.get"), calls)
        assertEquals(FailureCode.BridgeDenied, (engine.evaluate("source.put('unsupported','x')", frame) as ScriptResult.Failure).code)
    }

    @Test fun inputAndReturnedDataAreBoundedBeforeLeavingTheBridge() {
        var calls = 0
        val engine = RhinoScriptEngine(HostBridge { _, _ -> calls++; JsonPrimitive("x".repeat(100)) }, ScriptLimits(maxBridgeChars = 64))
        assertEquals(FailureCode.ResultTooLarge, (engine.evaluate("host.call('data', {x:'a'.repeat(100)})", frame) as ScriptResult.Failure).code)
        assertEquals(0, calls)
        assertEquals(FailureCode.ResultTooLarge, (engine.evaluate("host.call('data')", frame) as ScriptResult.Failure).code)
        assertEquals(1, calls)
    }

    @Test fun bridgeFailuresAreCatchableWithoutExposingHostExceptions() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("credential-do-not-expose") })
        assertEquals(FailureCode.BridgeDenied, (engine.evaluate("host.call('fail')", frame) as ScriptResult.Failure).code)
        val result = engine.evaluate("try{host.call('fail')}catch(e){[e.message,typeof e.getClass,typeof e.javaException]}", frame) as ScriptResult.Success
        assertEquals("[\"host bridge denied\",\"undefined\",\"undefined\"]", result.json)
    }

    @Test fun cancellationFromAnOutputGetterIsObservedAndContextIsCleanedUp() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> Thread.currentThread().interrupt(); JsonNull })
        try {
            val result = engine.evaluate("({get value(){host.call('interrupt');return 1}})", frame)
            assertEquals(FailureCode.Cancelled, (result as ScriptResult.Failure).code)
            assertNull(Context.getCurrentContext())
        } finally { Thread.interrupted() }
        assertEquals("1", (engine.evaluate("1", frame) as ScriptResult.Success).json)
    }

    @Test fun requestGlobalsAndInputDataAreNativeJsValues() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> JsonNull })
        val input = frame.copy(variables = mapOf("result" to Json.parseToJsonElement("{\"values\":[1,2]}")))
        assertEquals("[\"query\",3,\"https://fixture.invalid/\",3,\"undefined\"]", (engine.evaluate(
            "[key,page,baseUrl,result.values.reduce((a,b)=>a+b,0),typeof result.getClass]", input) as ScriptResult.Success).json)
    }
}
