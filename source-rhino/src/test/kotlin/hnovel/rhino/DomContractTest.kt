package hnovel.rhino

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DomContractTest {
    private val html = "<section><a href='/one' data-x='1'>One<b>!</b></a><a>Two</a></section>"
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
    private val frame = ScriptFrame("a", "legado", baseUrl = "https://fixture.invalid/book", variables = mapOf("result" to JsonPrimitive(html)))
    private fun check(expected: JsonElement, script: String) {
        val result = engine.evaluate(script, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        assertEquals(expected.toString(), (result as ScriptResult.Success).json)
    }
    @Test fun treeMutationsAttributesAndCollectionOverloadsMatchPinnedJsoup() {
        val reference = Jsoup.parse(html, frame.baseUrl)
        val a = reference.selectFirst("a")!!
        a.attr("title", "chapter").addClass("read").prependText("Start ").append("<i>End</i>")
        a.dataset()["x"] = "2"
        a.attributes().put("flag", true)
        val copy = a.clone().text("Copy")
        a.parent()!!.insertChildren(a.siblingIndex() + 1, listOf(copy))
        check(buildJsonArray { add(reference.select("section").html()); add(a.absUrl("href")); add(a.attributes().hasDeclaredValueForKey("flag")) }, """
            var a=java.getElements('section').first().selectFirst('a');
            a.attr('title','chapter').addClass('read').prependText('Start ').append('<i>End</i>');
            a.dataset().put('x','2');a.attributes().put('flag',true);
            var copy=a.clone().text('Copy');a.parent().insertChildren(a.siblingIndex()+1,[copy]);
            [a.parent().html(),a.absUrl('href'),a.attributes().hasDeclaredValueForKey('flag')]
        """)
    }
    @Test fun parserSettingsCallbacksAndNodeKindsRemainClosedData() {
        check(buildJsonArray { add(2); add("One!|Two"); add("p"); add("Hello"); add("undefined"); add("undefined") }, """
            var es=java.getElements('a'),texts=[];es.forEach(function(e){texts.push(e.text())});
            var doc=es.first().ownerDocument();doc.outputSettings().prettyPrint(false).charset('UTF-8');
            var p=doc.parser().setTrackErrors(4).setTrackPosition(true);
            var other=p.parseInput('<P>Hello</P>','https://fixture.invalid/');
            [texts.length,texts.join('|'),other.selectFirst('p').tag().getName(),other.body().text(),
             typeof doc.connection,typeof p.getClass]
        """)
        check(JsonPrimitive("One|Two"), """
            var root=java.getElements('section').first();root.filter({head:function(n,d){return n.nodeName()=='b'?'REMOVE':'CONTINUE'}});
            root.select('a').eachText().join('|')
        """)
    }
    @Test fun callbacksKeepInstructionBudgetAndSavedViewsKeepCurrentSizeBudget() {
        val limited = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(instructionLimit = 10000))
        assertEquals(FailureCode.Timeout, (limited.evaluate("java.getElements('a').forEach(function(){while(true){}})",frame) as ScriptResult.Failure).code)
        ScriptLibrary("a", "legado", "var saved={};").use { library ->
            assertTrue(engine.evaluate("saved.node=java.getElements('a').first();1", frame, library) is ScriptResult.Success)
            val small = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(maxBridgeChars=32))
            assertEquals(FailureCode.ResultTooLarge, (small.evaluate("saved.node.append('x')",frame.copy(variables=emptyMap()),library) as ScriptResult.Failure).code)
        }
    }

    @Test fun formDataAndTrackedRangesHaveUsableDataFacades() {
        check(buildJsonArray { add("chapter");add("one");add(true);add(1);add("undefined") }, """
            var parser=java.getElements('section').first().ownerDocument().parser().setTrackPosition(true);
            var doc=parser.parseInput('<form><input name=chapter value=one></form>','https://fixture.invalid/');
            var form=doc.forms().get(0),field=form.formData().get(0);
            [field.key(),field.value(),form.sourceRange().isTracked(),form.elements().size(),typeof form.submit]
        """)
    }
}
