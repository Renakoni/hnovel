package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScriptFontsTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name.ttf")!!.use { it.readBytes() }
    @Test fun syntheticGlyphsMapToTheirRealCodepointsThroughNativeMethods() {
        val plain = Base64.getEncoder().encodeToString(fixture("plain"))
        val obfuscated = Base64.getEncoder().encodeToString(fixture("obfuscated"))
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        ScriptLibrary("a", "legado", "var holder={};").use { library ->
            val code = """
                holder.good=java.queryTTF('$plain');holder.bad=java.queryBase64TTF('$obfuscated');
                [holder.good.getGlyfIdByUnicode(65),holder.bad.getGlyfIdByUnicode(57344),
                 holder.good.getUnicodeByGlyf(holder.bad.getGlyfByUnicode(57344)),
                 java.replaceFont('\uE000 Z',holder.bad,holder.good,true),typeof holder.good.getClass]
            """
            val result = engine.evaluate(code, ScriptFrame("a", "legado"), library)
            assertTrue(result.toString(), result is ScriptResult.Success)
            assertEquals("[1,1,65,\"A \",\"undefined\"]", (result as ScriptResult.Success).json)
            assertEquals(ScriptResult.Success("\"A\""), engine.evaluate("java.replaceFont('\uE000',holder.bad,holder.good)", ScriptFrame("a", "legado"), library))
            assertEquals(ScriptResult.Success("\"invalid font argument\""), engine.evaluate("try{holder.good.getGlyfById(9000)}catch(e){e.message}", ScriptFrame("a", "legado"), library))
        }
    }
    @Test fun malformedFontsAndForgedHandlesDoNotExposeJavaErrors() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        assertEquals(ScriptResult.Success("[\"host bridge denied\",\"undefined\"]"), engine.evaluate(
            "try{java.queryTTF([1,2,3])}catch(e){[e.message,typeof e.javaException]}", ScriptFrame("a", "legado")))
        assertEquals(ScriptResult.Success("\"host bridge denied\""), engine.evaluate(
            "try{java.replaceFont('a',{},null)}catch(e){e.message}", ScriptFrame("a", "legado")))
    }
}
