package hnovel.rhino

import org.junit.Assert.*
import org.junit.Test

class ScriptParsersTest {
    private val frame = ScriptFrame("novel", "legado", baseUrl = "https://source.invalid/book")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Parsing must stay in the worker") })

    @Test fun jsoupEntryUsesExistingDomMethodsAndExplicitBaseUri() {
        assertEquals(ScriptResult.Success("[\"Chapter\",\"https://text.invalid/next\",\"\",\"undefined\",\"undefined\",\"undefined\"]"), engine.evaluate("""
            var html='<article><blockquote>Discard</blockquote><a href="next">Chapter</a></article>';
            var doc=org.jsoup.Jsoup.parse(html,'https://text.invalid/book');
            doc.select('blockquote').remove();
            [doc.select('article').text(),doc.selectFirst('a').absUrl('href'),
             org.jsoup.Jsoup.parse(html).baseUri(),typeof doc.getClass,
             typeof org.jsoup.Jsoup.connect,typeof Packages]
        """, frame))
    }

    @Test fun libraryCanParseTextBeforeInvocationBindingsExist() {
        ScriptLibrary(frame.sourceId, frame.profile, "var doc=org.jsoup.Jsoup.parse('<p>Chapter</p>');").use { library ->
            assertEquals(ScriptResult.Success("\"Chapter\""), engine.evaluate("doc.select('p').text()", frame, library))
            assertEquals(ScriptResult.Success("\"Chapter\""), engine.evaluate("doc.select('p').text()", frame, library))
        }
    }

    @Test fun parserArgumentsAndRetainedMethodsUseCurrentInvocationBudget() {
        for (arguments in listOf("", "null", "'<p>chapter</p>',{}", "'<p>chapter</p>','','extra'")) {
            assertEquals(ScriptResult.Success("\"invalid parser argument\""),
                engine.evaluate("try{org.jsoup.Jsoup.parse($arguments)}catch(e){e.message}", frame))
        }
        ScriptLibrary(frame.sourceId, frame.profile, "var retained=org.jsoup.Jsoup.parse;").use { library ->
            assertEquals(ScriptResult.Success("\"ok\""), engine.evaluate("retained('<p>ok</p>').text()", frame, library))
            val small = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(maxBridgeChars = 128))
            val failed = small.evaluate("try{retained('x'.repeat(256))}catch(e){'hidden'}", frame, library)
            assertEquals(FailureCode.ResultTooLarge, (failed as ScriptResult.Failure).code)
            assertEquals(ScriptResult.Success("\"next\""), engine.evaluate("retained('<p>next</p>').text()", frame, library))
        }
    }
}
