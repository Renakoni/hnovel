package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptUrlsTest {
    private val engine=RhinoScriptEngine(HostBridge { _,_ -> error("No host") })
    @Test fun parsedUrlPropertiesAndQueryMapMatchPinnedJsUrl() {
        val result=engine.evaluate("""
            var url=java.toURL('../chapter?a=one+two&a=last&encoded%20key=%E9%BE%8D&bare','https://fixture.invalid:8443/book/');
            [url.host,url.getOrigin(),url.pathname,url.searchParams.get('a'),url.getSearchParams().get('encoded%20key'),
             url.searchParams.size(),java.toURL('https://fixture.invalid/').searchParams,typeof url.openConnection]
        """,ScriptFrame("a","legado")) as ScriptResult.Success
        assertEquals("[\"fixture.invalid\",\"https://fixture.invalid:8443\",\"/chapter\",\"last\",\"龍\",2,null,\"undefined\"]",result.json)
    }
    @Test fun chapterNumbersKeepReferenceFirstMatchAndUnknownNumberBehavior() {
        for ((input,expected) in listOf("prefix第一千二章 suffix" to "第1200章","第１２章" to "第12章","第未知章" to "第-1章","序章" to "序章","第一零二五章" to "第1025章")) {
            val result=engine.evaluate("java.toNumChapter(result)",ScriptFrame("a","legado",variables=mapOf("result" to JsonPrimitive(input)))) as ScriptResult.Success
            assertEquals(JsonPrimitive(expected).toString(),result.json)
        }
        assertEquals(ScriptResult.Success("null"),engine.evaluate("java.toNumChapter(null)",ScriptFrame("a","legado")))
    }
}
