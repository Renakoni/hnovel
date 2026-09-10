package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptRealmTest {
    private val frame = ScriptFrame("a", "legado", bookId = "book", variables = mapOf("result" to
        buildJsonObject { put("items", JsonArray(listOf(JsonPrimitive(1)))) }))
    private val engine = RhinoScriptEngine(HostBridge { name, _ ->
        if (name == "data") buildJsonObject { put("items", JsonArray(listOf(JsonPrimitive(2)))) } else error("secret")
    })

    @Test fun shadowedConstructorsCannotInterceptBoundaryConstructionOrRetainedCalls() {
        for (replacement in listOf("null", "function(){throw 'intercepted'}")) {
            ScriptLibrary("a", "legado", "var Object=$replacement, Array=$replacement, Function=$replacement, Error=$replacement;").use { library ->
                repeat(2) {
                    assertEquals(ScriptResult.Success("[true,1,2,\"host bridge denied\",\"invalid tool argument\"]"),
                        engine.evaluate("var message;try{host.call('denied')}catch(e){message=e.message;}" +
                            "var invalid;try{java.md5Encode()}catch(e){invalid=e.message;}" +
                            "[book.hasOwnProperty('id'),result.items.map(function(x){return x})[0]," +
                            "host.call.call(null,'data').items[0],message,invalid]", frame, library))
                }
            }
        }
    }

    @Test fun invocationReplacementsDoNotAffectJsonConversionOrErrors() {
        assertEquals(ScriptResult.Success("[2,\"host bridge denied\"]"), engine.evaluate(
            "Object=null;Array=null;Error=function(){throw 'intercepted'};" +
                "var value=host.call('data');var message;try{host.call('denied')}catch(e){message=e.message;}" +
                "[value.items[0],message]", frame))
    }
}
