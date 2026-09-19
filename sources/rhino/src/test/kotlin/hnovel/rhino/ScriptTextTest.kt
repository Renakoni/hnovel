package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptTextTest {
    @Test fun conversionAndExclusionsMatchPinnedPolicy() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host capability") })
        assertEquals(ScriptResult.Success("[\"龙与书\",\"龍與書\",\"鳳梨\",\"魔戒\"]"), engine.evaluate(
            "[java.t2s('龍與書'),java.s2t('龙与书'),java.t2s('鳳梨'),java.t2s('魔戒')]", ScriptFrame("a", "legado")))
        val threads = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = (1..12).map { threads.submit<String> { ScriptText.simplified("繁體小說龍與書") } }
            results.forEach { assertEquals("繁体小说龙与书", it.get()) }
        } finally { threads.shutdownNow() }
    }

    @Test fun fileBytesUseDetectionUnlessCharsetWasExplicit() {
        val content = "繁体小说与阅读章节".repeat(20)
        for (charset in listOf("UTF-8", "UTF-16LE", "GB18030", "Big5")) {
            val bytes = (if (charset == "Big5") "繁體小說與閱讀章節".repeat(20) else content).toByteArray(java.nio.charset.Charset.forName(charset))
            val engine = RhinoScriptEngine(HostBridge { name, _ -> assertEquals("java.readFile", name); ScriptTools.bytes(bytes) })
            val expected = bytes.toString(java.nio.charset.Charset.forName(ScriptText.charset(bytes, true)))
            assertEquals(ScriptResult.Success(JsonPrimitive(expected).toString()), engine.evaluate("java.readTxtFile('/fixture')", ScriptFrame("a", "legado")))
            assertEquals(ScriptResult.Success(JsonPrimitive(bytes.toString(java.nio.charset.Charset.forName(charset))).toString()),
                engine.evaluate("java.readTxtFile('/fixture','$charset')", ScriptFrame("a", "legado")))
        }
        assertEquals("UTF-8", ScriptText.charset(byteArrayOf(), true))
        assertEquals("UTF-8", ScriptText.charset("ascii".toByteArray(), true))
        assertEquals("UTF-8", ScriptText.charset(content.toByteArray(Charsets.UTF_8)))
        assertEquals("UTF-16LE", ScriptText.charset(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + content.toByteArray(Charsets.UTF_16LE)))
    }
}
