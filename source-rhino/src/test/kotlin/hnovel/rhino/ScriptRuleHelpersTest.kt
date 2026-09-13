package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptRuleHelpersTest {
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Selectors must stay in worker") })
    private val frame = ScriptFrame("a", "legado", variables = mapOf("result" to JsonPrimitive("<a href='/one'>One</a><a href='/two'>Two</a>")), baseUrl="https://example.org/base")
    private fun run(code: String, frame: ScriptFrame = this.frame): JsonElement {
        val result = engine.evaluate(code, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        return Json.parseToJsonElement((result as ScriptResult.Success).json)
    }

    @Test fun selectorsUseRootWhileNestedScriptsRestoreResultAndShareVariables() {
        val context = RuleContext("a", sourceVariables=mapOf("value" to "source"), chapterVariables=mapOf("value" to "chapter"))
        assertEquals(JsonArray(listOf(JsonPrimitive("One\nTwo"), JsonPrimitive("chapter!"), JsonPrimitive("outside"))), run("""
            result='outside';
            var title=java.getString('a@text');
            var changed=java.getString('@js:java.put("value",java.get("value")+"!");result');
            [title,java.get('value'),result]
        """, frame.copy(ruleContext=context)))
        assertEquals(mapOf("value" to "chapter!"), context.writes())
    }

    @Test fun explicitContentUrlListsAndNativeJsonNodesRetainTheirTypes() {
        assertEquals(JsonArray(listOf(JsonPrimitive("https://example.org/one"), JsonPrimitive("https://example.org/two"))),
            run("java.getStringList('a@href',null,true)"))
        assertEquals(JsonPrimitive("7"), run("java.getString('$.title',{title:7})"))
        val jsonFrame = frame.copy(variables=mapOf("result" to Json.parseToJsonElement("""{"books":[{"title":"one"},{"title":"two"}]}""")))
        assertEquals(JsonPrimitive("one"), run("java.getElement('$.books[0]').title", jsonFrame))
        assertEquals(JsonPrimitive("two"), run("java.getElements('$.books[*]')[1].title", jsonFrame))
    }

    @Test fun elementSnapshotsCanFeedSelectorsWithoutExposingDomObjects() {
        assertEquals(JsonPrimitive("One"), run("var nodes=java.getElements('a');java.getString('a@text',nodes[0])"))
        assertEquals(JsonPrimitive("undefined"), run("typeof java.getElements('a')[0].getClass"))
    }

    @Test fun emptyAndUnescapeOverloadsFollowReference() {
        assertEquals(JsonArray(listOf(JsonPrimitive(""), JsonNull, JsonNull, JsonArray(emptyList()), JsonPrimitive("&amp;"), JsonPrimitive("&"))), run("""
            [java.getString(null),java.getStringList(''),java.getElement(''),java.getElements(''),
             java.getString('@js:"&amp;"',false),java.getString('@js:"&amp;"',true)]
        """))
    }

    @Test fun nestedRuleRecursionCannotResetInstructionOrDepthBudget() {
        val frame = frame.copy(ruleBudget=RuleBudget(RuleLimits(maxDepth=8)))
        val result = engine.evaluate("function again(){return java.getString('@js:again()')}try{again()}catch(e){'hidden'}", frame)
        assertEquals(FailureCode.Timeout, (result as ScriptResult.Failure).code)
        assertEquals(JsonPrimitive("One\nTwo"), run("java.getString('a@text')"))
    }

    @Test fun setContentReplacesSelectorInputAndKeepsExplicitReadsTemporary() {
        assertEquals(JsonArray(listOf(JsonPrimitive("First"), JsonPrimitive("temporary"), JsonPrimitive("First"),
            JsonPrimitive("Second"), JsonPrimitive("Fourth"), JsonPrimitive("outside"), JsonPrimitive(true))), run("""
            result='outside';
            var same=java.setContent('<p>First</p>')===java;
            var first=java.getString('p@text');
            var temporary=java.getString('p@text','<p>temporary</p>');
            var retained=java.getString('p@text');
            java.setContent({chapter:{text:'Second'}});
            var second=java.getString('$.chapter.text');
            java.setContent([{title:'Third'},{title:'Fourth'}]);
            [first,temporary,retained,second,java.getString('$[1].title'),result,same]
        """))
        assertEquals(JsonPrimitive("One\nTwo"), run("java.getString('a@text')"))
    }

    @Test fun setContentUrlBaseIsLocalToSelectorsAndDomViews() {
        val context = RuleContext("a", baseUrl = frame.baseUrl)
        assertEquals(JsonArray(listOf(JsonPrimitive("https://text.invalid/folder/next"),
            JsonPrimitive("https://text.invalid/folder/next"), JsonPrimitive("https://text.invalid/folder/later"),
            JsonPrimitive(frame.baseUrl))), run("""
            java.setContent('<a href="next">Chapter</a>','https://text.invalid/folder/book');
            var url=java.getString('a@href',null,true);
            var dom=java.getElement('a').first().absUrl('href');
            java.setContent('<a href="later">Later</a>',null);
            [url,dom,java.getStringList('a@href',null,true)[0],baseUrl]
        """, frame.copy(ruleContext = context)))
        assertEquals(frame.baseUrl, context.baseUrl)
    }

    @Test fun invalidSetContentLeavesPreviousInputIntactAndCannotResetBudget() {
        assertEquals(JsonPrimitive("One\nTwo"), run("""
            try{java.setContent(null)}catch(e){}
            try{java.setContent('<p>bad</p>',{})}catch(e){}
            java.getString('a@text')
        """))
        val small = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(maxBridgeChars = 256))
        val failure = small.evaluate("try{java.setContent('x'.repeat(512))}catch(e){'hidden'}", frame)
        assertEquals(FailureCode.ResultTooLarge, (failure as ScriptResult.Failure).code)
    }
}
