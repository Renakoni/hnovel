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
}
