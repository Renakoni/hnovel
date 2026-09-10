package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScriptFontsTest {
    @Test fun malformedTableExtentsFailBeforeAllocationAndSupplementaryCmapWorks() {
        val bytes = fixture("plain")
        val directory = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val count = directory.getShort(4).toInt() and 65535
        val loca = (0 until count).map { 12 + it * 16 }.first { bytes.copyOfRange(it, it + 4).toString(Charsets.US_ASCII) == "loca" }
        for ((field, value) in listOf(12 to Int.MAX_VALUE, 8 to Int.MAX_VALUE, 8 to -1)) {
            val bad = bytes.copyOf(); java.nio.ByteBuffer.wrap(bad).putInt(loca + field, value)
            assertTrue(runCatching { hnovel.rhino.font.QueryTTF(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
        val supplementary = fixture("supplementary")
        val parser = hnovel.rhino.font.QueryTTF(supplementary)
        assertEquals(1, parser.getGlyfIdByUnicode(0x100000))
        assertEquals(65, hnovel.rhino.font.QueryTTF(bytes).getUnicodeByGlyf(parser.getGlyfByUnicode(0x100000)))
    }
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
