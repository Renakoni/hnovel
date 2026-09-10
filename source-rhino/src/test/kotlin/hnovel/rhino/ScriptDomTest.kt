package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptDomTest {
    private val html = "<section><a href='/chapter'>One<b>!</b></a><a>Two</a></section>"
    private val frame = ScriptFrame("a", "legado", baseUrl = "https://fixture.invalid/book", variables = mapOf("result" to JsonPrimitive(html)))
    @Test fun elementMethodsChainAndRemainMarkupAtRuleBoundaries() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        assertEquals(ScriptResult.Success("[2,\"One!\",\"One\",\"/chapter\",\"https://fixture.invalid/chapter\",\"!\",\"One!\",\"undefined\"]"), engine.evaluate("""
            var es=java.getElements('a');var a=es.get(0);
            [es.size(),a.text(),a.ownText(),a.attr('href'),a.absUrl('href'),a.select('b').first().text(),
             java.getString('a@text',a),typeof a.getClass]
        """, frame))
        assertEquals(ScriptResult.Success("\"invalid DOM argument\""), engine.evaluate("try{java.getElements('a').get(99)}catch(e){e.message}", frame))
    }
    @Test fun responseParseUsesFinalUrlAndRetainedMethodsUseCurrentBudget() {
        val data = buildJsonObject { put("body", html); put("url", frame.baseUrl); put("status", 200); put("message", "OK"); put("headers", JsonObject(emptyMap())) }
        val engine = RhinoScriptEngine(HostBridge { _, _ -> data })
        ScriptLibrary("a", "legado", "var holder={};").use { library ->
            assertEquals(ScriptResult.Success("\"https://fixture.invalid/chapter\""), engine.evaluate(
                "holder.doc=java.get('url',{}).parse();holder.doc.selectFirst('a').absUrl('href')", frame, library))
            val small = RhinoScriptEngine(HostBridge { _, _ -> data }, ScriptLimits(maxBridgeChars = 32))
            assertEquals(FailureCode.ResultTooLarge, (small.evaluate("holder.doc.outerHtml()", frame.copy(variables=emptyMap()), library) as ScriptResult.Failure).code)
        }
    }
}
