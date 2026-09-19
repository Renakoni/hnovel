package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScriptFontsTest {
    // Hand-encoded cmap tables exercise glyph-array deltas which FontBuilder optimizes away.
    private fun withCmap(subtable: ByteArray): ByteArray {
        val original = fixture("plain")
        val font = original.copyOf(original.size + 12 + subtable.size)
        val data = java.nio.ByteBuffer.wrap(font)
        val count = data.getShort(4).toInt() and 65535
        val entry = (0 until count).map { 12 + it * 16 }.first {
            font.copyOfRange(it, it + 4).toString(Charsets.US_ASCII) == "cmap"
        }
        data.putInt(entry + 8, original.size).putInt(entry + 12, 12 + subtable.size)
        data.position(original.size)
        data.putShort(0).putShort(1).putShort(3).putShort(1).putInt(12).put(subtable)
        return font
    }
    private fun format4(delta: Int, glyphs: List<Int>): ByteArray {
        val length = 32 + glyphs.size * 2
        return java.nio.ByteBuffer.allocate(length).apply {
            // Two segments: the test range and the required U+FFFF sentinel.
            (listOf(4, length, 0, 4, 4, 1, 0, 0xE000 + glyphs.lastIndex, 0xFFFF, 0,
                0xE000, 0xFFFF, delta, 1, 4, 0) + glyphs).forEach { putShort(it.toShort()) }
        }.array()
    }

    @Test fun format4PreservesMissingGlyphsAndWrapsDeltasBeforeFontReplacement() {
        val plain = Base64.getEncoder().encodeToString(fixture("plain"))
        // Raw zero must remain missing, 65535 + 2 wraps to glyph 1, 65534 + 2 to missing.
        for ((delta, glyphs, expected) in listOf(
            Triple(2, listOf(0, 65535, 65534), listOf(0, 1, 0)),
            Triple(-1, listOf(0, 2, 1), listOf(0, 1, 0)),
            Triple(0, listOf(0, 1, 0), listOf(0, 1, 0)))) {
            val bytes = withCmap(format4(delta, glyphs))
            val parser = hnovel.rhino.font.QueryTTF(bytes)
            assertEquals(expected, (0..2).map { parser.getGlyfIdByUnicode(0xE000 + it) })
            assertNull(parser.getGlyfByUnicode(0xE000))
            val encoded = Base64.getEncoder().encodeToString(bytes)
            assertEquals(ScriptResult.Success("[\"A\",\"\uE000A\uE002\"]"),
                RhinoScriptEngine(HostBridge { _, _ -> error("No host") }).evaluate("""
                    var bad=java.queryTTF('$encoded'),good=java.queryTTF('$plain');
                    [java.replaceFont('\uE000\uE001\uE002',bad,good,true),java.replaceFont('\uE000\uE001\uE002',bad,good)]
                """, ScriptFrame("a", "legado")))
        }
    }

    @Test fun malformedFormat4SegmentsCannotReadOutsideTheirGlyphArray() {
        for ((offset, value) in listOf(6 to 0, 6 to 3, 20 to 0xE003, 28 to 2, 28 to 5, 28 to 65534)) {
            val subtable = format4(2, listOf(0, 65535, 65534))
            java.nio.ByteBuffer.wrap(subtable).putShort(offset, value.toShort())
            assertTrue("offset=$offset, value=$value", runCatching {
                hnovel.rhino.font.QueryTTF(withCmap(subtable))
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

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
