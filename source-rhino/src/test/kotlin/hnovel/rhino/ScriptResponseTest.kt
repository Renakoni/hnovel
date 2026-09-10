package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptResponseTest {
    private val data = buildJsonObject {
        put("body", "chapter"); put("url", "https://fixture.invalid/final"); put("status", 200); put("message", "OK")
        put("headers", buildJsonObject { put("X-Test", JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two")))) })
    }
    private val engine = RhinoScriptEngine(HostBridge { name, _ -> if (name == "java.ajaxAll") JsonArray(listOf(data, data)) else data })
    private val frame = ScriptFrame("a", "legado")

    @Test fun responseViewsExposeDataMethodsWithoutJavaWrappers() {
        assertEquals(ScriptResult.Success("[\"chapter\",200,\"OK\",true,\"two\",[\"one\",\"two\"],\"undefined\",\"undefined\"]"), engine.evaluate(
            "var r=java.connect('url');[r.body(),r.code(),r.message(),r.isSuccessful(),r.headers().get('x-test')," +
                "r.headers().values('X-Test'),typeof r.getClass,typeof r.raw]", frame))
        assertEquals(ScriptResult.Success("[\"chapter\",200,\"OK\",null,\"one\",\"one, two\",null]"), engine.evaluate(
            "var r=java.get('url',{});[r.body.call(null),r.statusCode(),r.statusMessage(),r.header('missing'),r.headers().get('X-Test'),r.header('x-test'),r.headers().get('x-test')]", frame))
        assertEquals(ScriptResult.Success("[\"chapter\",\"chapter\"]"), engine.evaluate("java.ajaxAll(['a','b']).map(function(r){return r.body()})", frame))
    }

    @Test fun responseMethodsSurviveShadowedConstructorsAndKeepDataBounds() {
        ScriptLibrary("a", "legado", "var Object=null,Array=null,Function=null,Error=null;var holder={};").use { library ->
            assertEquals(ScriptResult.Success("1"), engine.evaluate("holder.response=java.connect('url');1", frame, library))
            assertEquals(ScriptResult.Success("\"chapter\""), engine.evaluate("holder.response.body()", frame, library))
        }
        val small = RhinoScriptEngine(HostBridge { _, _ -> data }, ScriptLimits(maxBridgeChars = 64))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate("java.connect('url')", frame) as ScriptResult.Failure).code)
    }
}
