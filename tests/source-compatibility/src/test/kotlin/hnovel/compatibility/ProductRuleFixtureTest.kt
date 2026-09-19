package hnovel.compatibility

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import hnovel.rules.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Executes the production layer and compares it with hand-derived outputs AND the pinned selectors. */
@RunWith(Parameterized::class)
class ProductRuleFixtureTest(private val case: com.google.gson.JsonObject) {
    @Test fun productionMatchesFixedSelectorContract() {
        val input = case.get("input")?.asString?.let(FixtureCorpus::text).orEmpty()
        val rule = if (case.has("source")) {
            var value: JsonElement = FixtureCorpus.json("sources.json").getAsJsonArray("sources")[case.get("source").asInt]
            for (field in case.string("field").split('.')) value = value.asJsonObject.get(field)
            value.asString
        } else case.string("rule")
        val operation = case.string("operation")
        val result = if (operation == "split") FixtureCorpus.gson.toJsonTree(
            RuleParser().split(rule, listOf("&&", "||", "%%"), RuleLocation("fixture"), RuleBudget()).second.map { it.text })
        else {
            val output = when (operation) {
                "html-scalar", "json-scalar" -> OutputKind.Text
                "regex" -> OutputKind.Elements
                "regex-first" -> OutputKind.Element
                else -> OutputKind.TextList
            }
            val evaluated = RuleEvaluator().evaluate(if (operation.startsWith("regex")) ":$rule" else rule,
                RuleValue.Text(input), RuleContext("fixture"), output, RuleLocation(case.get("field")?.asString ?: case.string("id")))
            if (case.has("expectedError")) {
                assertTrue(evaluated is RuleResult.Failure)
                assertEquals(case.string("expectedError"), (evaluated as RuleResult.Failure).error.code)
                assertThrows(NullPointerException::class.java) { ReferenceRunner.evaluate(case) }
                return
            }
            assertTrue(evaluated.toString(), evaluated is RuleResult.Success)
            json((evaluated as RuleResult.Success).value)
        }
        FixtureCorpus.assertOutput(case.string("id"), case.get("expected"), result)
        assertEquals(ReferenceRunner.evaluate(case), result)
    }

    private fun json(value: RuleValue): JsonElement = when (value) {
        RuleValue.Empty -> JsonNull.INSTANCE
        is RuleValue.Text -> FixtureCorpus.gson.toJsonTree(value.value)
        is RuleValue.Items -> FixtureCorpus.gson.toJsonTree(value.values.map(::json))
        is RuleValue.Captures -> FixtureCorpus.gson.toJsonTree(value.groups)
        is RuleValue.Node -> FixtureCorpus.gson.toJsonTree(value.content)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = FixtureCorpus.cases().filter { it.string("oracle") == "pinned-selector" }.map { arrayOf(it) }
    }
}
