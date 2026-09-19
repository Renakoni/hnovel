package hnovel.compatibility

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.model.analyzeRule.AnalyzeByJSonPath
import io.legado.app.model.analyzeRule.AnalyzeByJSoup
import io.legado.app.model.analyzeRule.AnalyzeByRegex
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import io.legado.app.model.analyzeRule.RuleAnalyzer
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/** Calls the pinned selectors; it is not the app's future rule interpreter. */
object ReferenceRunner {
    fun evaluate(case: JsonObject): JsonElement {
        val input = case.get("input")?.asString?.let(FixtureCorpus::text).orEmpty()
        val rule = if (case.has("source")) {
            var value: JsonElement = FixtureCorpus.json("sources.json").getAsJsonArray("sources")[case.get("source").asInt]
            for (field in case.string("field").split('.')) value = value.asJsonObject.get(field)
            value.asString
        } else case.get("rule")?.asString.orEmpty()
        val output: Any? = when (case.string("operation")) {
            "html" -> AnalyzeByJSoup(input).getStringList(rule)
            "html-scalar" -> AnalyzeByJSoup(input).getString(rule)
            "json" -> AnalyzeByJSonPath(input).getStringList(rule)
            "json-scalar" -> AnalyzeByJSonPath(input).getString(rule)
            "xpath" -> AnalyzeByXPath(input).getStringList(rule)
            "regex" -> AnalyzeByRegex.getElements(input, arrayOf(rule))
            "regex-first" -> AnalyzeByRegex.getElement(input, arrayOf(rule))
            "split" -> RuleAnalyzer(rule, true).splitRule("&&", "||", "%%")
            "script", "html-script" -> return script(case, input, rule)
            else -> error("Unknown fixture operation: ${case.string("operation")}")
        }
        return FixtureCorpus.gson.toJsonTree(output)
    }

    // Trusted, checked-in synthetic scripts only. This is NOT a production sandbox or
    // an implementation of Legado's Java bridge. Host doubles live in fixtures/host.js.
    private fun script(case: JsonObject, input: String, rule: String): JsonElement {
        val factory = object : ContextFactory() {
            private var instructions = 0
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                instructionObserverThreshold = 1000
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                instructions += instructionCount
                check(instructions <= 1_000_000) { "Fixture instruction budget exceeded" }
            }
        }
        return factory.call { cx ->
            val scope = cx.initSafeStandardObjects()
            val data: Any = if (case.string("operation") == "html-script") {
                AnalyzeByJSoup(input).getStringList(rule)
            } else input
            cx.evaluateString(scope, "var input = ${FixtureCorpus.gson.toJson(data)};", "input", 1, null)
            case.get("host")?.asString?.let {
                val host = FixtureCorpus.text(it)
                cx.evaluateString(scope, "var fixtureHost = $host;", it, 1, null)
                cx.evaluateString(scope, FixtureCorpus.text("fixtures/host.js"), "host-double", 1, null)
            }
            case.get("library")?.asString?.let {
                cx.evaluateString(scope, FixtureCorpus.text(it), it, 1, null)
            }
            val scriptPath = case.string("script")
            val script = FixtureCorpus.text(scriptPath)
            val result = cx.evaluateString(scope, "JSON.stringify((function() {\n$script\n})())", scriptPath, 1, null)
            JsonParser.parseString(Context.toString(result))
        }
    }
}
