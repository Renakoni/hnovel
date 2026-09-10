package hnovel.rhino

import hnovel.rules.RuleContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MetadataContractTest {
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
    private fun run(script: String, frame: ScriptFrame): String {
        val result = engine.evaluate(script, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        return (result as ScriptResult.Success).json
    }

    @Test fun convertedBooksHaveIndependentBigValuesAndPersistenceWrites() {
        val context = RuleContext("a", bookBigVariables=mapOf("kept" to "original"))
        val frame = ScriptFrame("a", "legado", ruleContext=context)
        assertEquals("[\"original\",\"original\",\"search\",\"copy\",\"\"]", run("""
            var search=book.toSearchBook(),copy=book.toBook(),inherited=search.getBigVariable('kept');
            search.putBigVariable('kept','search');copy.putBigVariable('kept','copy');
            search.putVariable('large',new Array(10001).join('x'));
            [inherited,book.getBigVariable('kept'),search.getBigVariable('kept'),copy.getBigVariable('kept'),book.getVariable('large')]
        """,frame))
        assertEquals(mapOf("kept" to "original"), context.bookBigValues)
        assertTrue(context.bookBigWrites.isEmpty())
        assertTrue(context.bookWrites.isEmpty())
    }
    @Test fun smallBigVariablesMoveDeleteAndInitializeLazilyAcrossStages() {
        val context = RuleContext("a")
        val frame = ScriptFrame("a", "legado", ruleContext=context,
            book=buildJsonObject { put("variable", "{\"old\":\"initial\"}") })
        assertEquals("[\"changed\",\"changed\",10000,false,9999,null]", run("""
            book.variable='{"old":"changed"}';var old=book.getVariable('old');
            book.variable='{"old":"ignored"}';var retained=book.getVariable('old');
            book.putVariable('large',new Array(10001).join('x'));
            var length=book.getBigVariable('large').length,small=book.variableMap.containsKey('large');
            book.putVariable('large',new Array(10000).join('y'));
            [old,retained,length,small,book.getVariable('large').length,book.getBigVariable('large')]
        """, frame))
        assertEquals(9999, context.bookWrites["large"]!!.length)
        assertTrue(context.bookBigWrites.containsKey("large")); assertNull(context.bookBigWrites["large"])
        assertEquals("[\"changed\",\"\",null]",run("book.putVariable('large',null);[book.getVariable('old'),book.getVariable('large'),book.getBigVariable('large')]",frame))
        assertNull(context.bookWrites["large"])
        assertEquals("\"\"",run("book.getVariable('old')",frame.copy(ruleContext=RuleContext("b"),book=JsonObject(emptyMap()))))
    }
    @Test fun liveVariableMapTracksWritesWithoutReserializingUntilPutVariable() {
        val context=RuleContext("a")
        val frame=ScriptFrame("a","legado",ruleContext=context)
        assertEquals("[\"new\",null,\"new\"]",run("""
            var map=book.variableMap;map.put('token','old');map.entrySet()[0].setValue('new');
            [map.token,book.variable,book.getVariable('token')]
        """,frame))
        assertEquals(mapOf("token" to "new"),context.bookWrites)
        assertEquals("[\"new\",\"{\\\"next\\\":\\\"value\\\"}\"]",run("""
            var old=book.variableMap.remove('token');book.putVariable('next','value');[old,book.variable]
        """,frame))
        assertNull(context.bookWrites["token"])
    }
    @Test fun entityHelpersCaptureChangesAndChapterUrlFallbacks() {
        val frame=ScriptFrame("a","legado",book=buildJsonObject {
            put("name","A/B.Book");put("author","作者： Someone 著");put("totalChapterNum",10);put("durChapterIndex",3)
            put("intro","original");put("bookUrl","https://fixture.invalid/book")
        },chapter=buildJsonObject { put("title","龍\n");put("index",4) },chineseConverter=1)
        assertEquals("[\"Someone\",9,6,\"original\",\"utf-8\",\"龙\",true]",run("""
            book.upCustomIntro();var file=chapter.getFileName();chapter.title='changed';
            [book.getRealAuthor(),book.getLastChapterIndex(),book.getUnreadChapterNum(),book.getDisplayIntro(),
             book.fileCharset().name().toLowerCase(),(chapter.title='龍\n',chapter.getDisplayTitle()),file==chapter.getFileName()]
        """,frame))
        assertEquals("[\"A/B.Book\",\"changed\",\"\",\"novel\",\"龙\"]",run("""
            book.kind='novel';var search=book.toSearchBook();search.setName('changed');search.putVariable('local','value');
            [book.name,search.toBook().name,book.getVariable('local'),book.getKindList().get(0),
             chapter.getDisplayTitle([{pattern:'[',replacement:'x',isRegex:true}])]
        """,frame))
        for ((base,url,expected) in listOf(Triple(""," https://fixture.invalid/a ","https://fixture.invalid/a"),
            Triple("invalid"," ../a ","../a"),Triple("https://fixture.invalid/toc,{\"method\":\"POST\"}","one","https://fixture.invalid/one"),
            Triple("https://fixture.invalid/","javascript:ignored",""),Triple("https://fixture.invalid/","data:text/plain,a","data:text/plain,a"))) {
            assertEquals(JsonPrimitive(expected).toString(),run("chapter.getAbsoluteURL()",frame.copy(chapter=buildJsonObject { put("baseUrl",base);put("url",url) })))
        }
    }
}
