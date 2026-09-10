package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptRequestTemplatesTest {
    private val frame = ScriptFrame("a", "legado", variables=mapOf("result" to JsonPrimitive("outer")), key="a b", page=2, baseUrl="https://example.org/books/")
    private fun expanded(rule: String): String {
        var actual = ""
        val engine = RhinoScriptEngine(HostBridge { _, args -> actual=args[0].jsonPrimitive.content; JsonPrimitive("ok") })
        val result = engine.evaluate("java.ajax(${JsonPrimitive(rule)});result", frame)
        assertEquals(ScriptResult.Success("\"outer\""), result)
        return actual
    }

    @Test fun scriptStagesAndNestedExpressionsRunBeforeHostDispatch() {
        assertEquals("/next?p=2", expanded("/start<js>result.replace('start','next')</js>@result?p={{page+0}}"))
        assertEquals("/find?q=a%20b", expanded("@js:'/find?q='+encodeURIComponent(key)"))
        assertEquals("/x?value=3", expanded("/x?value={{({a:{b:3}}).a.b}}"))
        assertEquals("/x?value=", expanded("/x?value={{null}}"))
        assertEquals("/it's-a-book", expanded("/it's-a-book"))
    }

    @Test fun optionScriptReceivesResolvedUrlAndKeepsOtherOptions() {
        assertEquals("https://example.org/books/two?next=2,{\"method\":\"POST\",\"body\":\"key={{key}}\"}",
            expanded("""<one,two>,{"method":"POST","body":"key={{key}}","js":"result+'?next='+page"}"""))
        assertEquals("https://example.org/new,{}", expanded("""start,{"js":"baseUrl+'/new'"}"""))
    }

    @Test fun staticPlaceholdersRemainForCharsetAwareHostCompiler() {
        assertEquals("/find?q={{key}}&p={{page}}&n=3", expanded("/find?q={{key}}&p={{page}}&n={{page+1}}"))
    }

    @Test fun runawayAndOversizedTemplatesNeverReachHost() {
        var called = false
        val engine = RhinoScriptEngine(HostBridge { _, _ -> called=true; JsonNull }, ScriptLimits(maxBridgeChars=128))
        assertEquals(FailureCode.Timeout, (engine.evaluate("java.ajax('@js:while(true){}')", frame) as ScriptResult.Failure).code)
        assertEquals(FailureCode.ResultTooLarge, (engine.evaluate("java.ajax('/x?q={{\"a\".repeat(1000)}}')", frame) as ScriptResult.Failure).code)
        assertFalse(called)
    }
}
