package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptHtmlTest {
    @Test fun formattingPreservesPinnedIndentationImagesAndEntityPolicy() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Formatting must be local") })
        val frame = ScriptFrame("a", "legado")
        fun format(input: String): String = Json.parseToJsonElement((engine.evaluate("java.htmlFormat(${JsonPrimitive(input)})", frame) as ScriptResult.Success).json).jsonPrimitive.content
        assertEquals("\u3000\u3000First\n\u3000\u3000Second", format("<p>First</p><p><b>Second</b></p>"))
        assertEquals("A <img src=\"/cover.jpg\">B&amp;C",
            format("A&nbsp;&nbsp;<img class='cover' data-src='/cover.jpg'>B&amp;C<!--hidden-->"))
        assertEquals("text", format("&thinsp;text&zwj;"))
    }
}
