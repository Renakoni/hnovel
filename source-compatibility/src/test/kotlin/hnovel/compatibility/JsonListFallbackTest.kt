package hnovel.compatibility

import hnovel.rules.*
import io.legado.app.model.analyzeRule.AnalyzeByJSonPath
import org.junit.Assert.*
import org.junit.Test

class JsonListFallbackTest {
    @Test fun nonListBranchesFallThroughWithoutChangingObjectSelection() {
        val input = """{"data":{"list":[{"id":1},{"id":2}]},"scalar":7,"empty":[],"nullValue":null}"""
        for (rule in listOf("data||data.list", "scalar||data.list", "empty||data.list", "nullValue||data.list", "missing||data.list")) {
            val reference = AnalyzeByJSonPath(input).getList(rule)
            assertEquals(rule, 2, reference!!.size)
            val result = RuleEvaluator().evaluate(rule, RuleValue.Text(input), RuleContext("fixture"), OutputKind.Elements)
            assertTrue("$rule: $result", result is RuleResult.Success)
            assertEquals(rule, listOf("{\"id\":1}", "{\"id\":2}"), ((result as RuleResult.Success).value as RuleValue.Items).values.map { (it as RuleValue.Node).content })
        }
        val objectResult = RuleEvaluator().evaluate("data", RuleValue.Text(input), RuleContext("fixture"), OutputKind.Element)
        assertEquals("{\"list\":[{\"id\":1},{\"id\":2}]}", ((objectResult as RuleResult.Success).value as RuleValue.Node).content)
        assertThrows(IllegalArgumentException::class.java) { AnalyzeByJSonPath("").getList("data||data.list") }
        assertTrue(RuleEvaluator().evaluate("$.data||$.data.list", RuleValue.Text(""), RuleContext("fixture"), OutputKind.Elements) is RuleResult.Failure)
    }
}
