package hnovel.rules

import com.google.gson.JsonParser
import hnovel.rules.selector.AnalyzeByJSonPath
import hnovel.rules.selector.AnalyzeByJSoup
import hnovel.rules.selector.AnalyzeByXPath
import org.jsoup.parser.Parser
import java.net.URI

/** Stateless evaluator; callers supply a distinct context and budget per request. No IO is performed here. */
class RuleEvaluator(private val unescapeHtml: Boolean = true, private val scripts: RuleScriptPort? = null) {
    private val parser = RuleParser()

    fun evaluate(rule: String, input: RuleValue, context: RuleContext, output: OutputKind = OutputKind.TextList,
        location: RuleLocation = RuleLocation("rule"), budget: RuleBudget = RuleBudget(), urlBase: String = context.baseUrl): RuleResult = try {
        budget.checkValue(input, budget.limits.maxInputChars)
        val value = run(rule, input, input, context, output, location, budget, 0)
        budget.checkValue(value, budget.limits.maxOutputChars)
        RuleResult.Success(when (output) {
            OutputKind.Url -> RuleValue.Text(absolute(urlBase, value.items().firstOrNull()?.text().orEmpty(), true))
            OutputKind.UrlList -> RuleValue.Items(value.items().map { absolute(urlBase, it.text(), false) }
                .filter { it.isNotEmpty() }.distinct().map(RuleValue::Text))
            else -> value
        })
    } catch (failure: RuleFailure) {
        RuleResult.Failure(failure.error)
    } catch (_: RuleBudgetExceeded) {
        RuleResult.Failure(RuleError(RuleStage.Budget, location, "BudgetExceeded"))
    } catch (_: StackOverflowError) {
        RuleResult.Failure(RuleError(RuleStage.Budget, location, "StackLimitExceeded"))
    } catch (failure: Exception) {
        RuleResult.Failure(RuleError(RuleStage.Select, location, failure.javaClass.simpleName))
    }

    private fun run(rule: String, input: RuleValue, root: RuleValue, context: RuleContext, output: OutputKind,
        location: RuleLocation, budget: RuleBudget, depth: Int): RuleValue {
        budget.check()
        if (depth > budget.limits.maxDepth) throw RuleBudgetExceeded()
        var value = input
        val plan = parser.parse(rule, location, budget)
        if (plan.steps.isEmpty()) return RuleValue.Empty
        for (step in plan.steps) {
            val at = location.copy(offset = location.offset + step.offset)
            value = if (step.script) script(step.text, value, context, at, budget)
                else select(step.text, value, root, context, output, at, budget, depth + 1)
            budget.checkValue(value, budget.limits.maxOutputChars)
        }
        return value
    }

    private fun select(raw: String, input: RuleValue, root: RuleValue, context: RuleContext, output: OutputKind,
        location: RuleLocation, budget: RuleBudget, depth: Int): RuleValue {
        var rule = raw.trim()
        // Put expressions evaluate against the request's original content, before the field itself.
        var index = 0
        val withoutPuts = StringBuilder()
        while (index < rule.length) {
            budget.check()
            if (rule.startsWith("##", index)) {
                withoutPuts.append(rule.substring(index))
                break
            } else if (rule.regionMatches(index, "@put:", 0, 5, true)) {
                val start = index + 5
                if (rule.getOrNull(start) != '{') fail(RuleStage.Parse, location, index, "InvalidPut")
                val end = parser.balancedEnd(rule, start, location, budget)
                val entries = try { JsonParser.parseString(rule.substring(start, end)).asJsonObject }
                    catch (_: Exception) { fail(RuleStage.Parse, location, index, "InvalidPut") }
                for ((key, expression) in entries.entrySet()) {
                    if (!expression.isJsonPrimitive || !expression.asJsonPrimitive.isString) fail(RuleStage.Parse, location, index, "InvalidPutValue")
                    context.put(key, run(expression.asString, root, root, context, OutputKind.Text,
                        location.copy(offset = location.offset + start), budget, depth).text())
                }
                index = end
            } else if (rule[index] in "[({") {
                val end = parser.balancedEnd(rule, index, location, budget)
                withoutPuts.append(rule.substring(index, end))
                index = end
            } else { withoutPuts.append(rule[index]); index++ }
        }
        rule = withoutPuts.toString()
        val (_, replacement) = parser.split(rule, listOf("##"), location, budget)
        var selector = replacement.first().text.trim()
        val literal = selector.contains("@get:", true) || selector.contains("{{") ||
            (input is RuleValue.Captures && Regex("\\$[0-9]{1,2}").containsMatchIn(selector))
        selector = interpolate(selector, input, root, context, location, budget, depth)
        var value = if (selector.isEmpty() && replacement.size > 1) input
            else if (literal) RuleValue.Text(selector)
            else selectors(selector, input, output, location, budget, depth)
        if (replacement.size > 1) {
            val pattern = interpolate(replacement[1].text, input, root, context, location, budget, depth, captures = false)
            val to = replacement.getOrNull(2)?.text?.let { interpolate(it, input, root, context, location, budget, depth, captures = false) }.orEmpty()
            value = atStage(RuleStage.Replace, location) {
                fun replace(value: RuleValue) = RuleValue.Text(RegexRules.replace(value.text(), pattern, to, replacement.size > 3, budget))
                if (value is RuleValue.Items) RuleValue.Items(value.values.map(::replace)) else replace(value)
            }
        }
        return if (output == OutputKind.Text || output == OutputKind.Url) {
            if (value == RuleValue.Empty) value else {
                val text = if (output == OutputKind.Url) value.items().firstOrNull()?.text().orEmpty() else value.text()
                RuleValue.Text(if (unescapeHtml) Parser.unescapeEntities(text, false) else text)
            }
        } else if (output == OutputKind.TextList || output == OutputKind.UrlList) {
            if (value is RuleValue.Text) RuleValue.Items(value.value.split('\n').map(RuleValue::Text)) else value
        } else value
    }

    private fun selectors(rule: String, input: RuleValue, output: OutputKind, location: RuleLocation,
        budget: RuleBudget, depth: Int, inherited: String? = null): RuleValue {
        budget.check()
        if (depth > budget.limits.maxDepth) throw RuleBudgetExceeded()
        val elements = output == OutputKind.Element || output == OutputKind.Elements
        if (elements && rule.startsWith(':')) return atStage(RuleStage.Select, location) {
            RegexRules.extract(input.text(), parser.split(rule.substring(1), listOf("&&"), location, budget).second.map { it.text },
                output == OutputKind.Element, budget)
        }
        val mode = when {
            rule.startsWith("@CSS:", true) -> "css"
            rule.startsWith("@@") -> "html"
            rule.startsWith("@Json:", true) -> "json"
            rule.startsWith("@XPath:", true) -> "xpath"
            inherited != null -> inherited
            input is RuleValue.Node && input.kind == InputKind.Json -> "json"
            rule.startsWith("$.") || rule.startsWith("$[") -> "json"
            rule.startsWith('/') -> "xpath"
            input.text().trimStart().let { it.startsWith('{') || it.startsWith('[') } -> "json"
            else -> "html"
        }
        val local = when {
            rule.startsWith("@@") -> rule.substring(2)
            rule.startsWith("@CSS:", true) -> rule.substring(5)
            rule.startsWith("@Json:", true) -> rule.substring(6)
            rule.startsWith("@XPath:", true) -> rule.substring(7)
            else -> rule
        }
        val operators = if (output == OutputKind.Text && mode in listOf("json", "xpath")) listOf("&&", "||") else listOf("&&", "||", "%%")
        val (operator, parts) = parser.split(local, operators, location, budget)
        if (operator != null) {
            val branches = mutableListOf<List<RuleValue>>()
            var characters = 0L
            for (part in parts) {
                val found = selectors(part.text.trim(), input, output, location.copy(offset = location.offset + part.offset), budget, depth + 1, mode).items()
                characters += budget.checkValue(RuleValue.Items(found), budget.limits.maxOutputChars)
                if (characters > budget.limits.maxOutputChars) throw RuleBudgetExceeded()
                if (found.isNotEmpty() && found.any { it.text().isNotEmpty() }) {
                    branches.add(found)
                    if (operator == "||") break
                }
            }
            // Pinned %% stops at the first non-empty branch's length (including its uneven-tail quirk).
            val joined = if (operator == "%%" && branches.isNotEmpty()) branches.first().indices.flatMap { i -> branches.mapNotNull { it.getOrNull(i) } }
                else branches.flatten()
            return if (output == OutputKind.Text) RuleValue.Text(joined.joinToString("\n") { it.text() }) else RuleValue.Items(joined)
        }
        return atStage(RuleStage.Select, location) {
            val body = input.text()
            val list = output in listOf(OutputKind.TextList, OutputKind.UrlList)
            when (mode) {
                "json" -> {
                    val selector = AnalyzeByJSonPath(body)
                    when {
                        elements -> {
                            val selected = if (output == OutputKind.Element) listOf(selector.getObject(local)) else selector.getList(local).orEmpty()
                            RuleValue.Items(selected.map { RuleValue.Node(com.google.gson.Gson().toJson(it), InputKind.Json) })
                        }
                        list -> RuleValue.Items(selector.getStringList(local).map(RuleValue::Text))
                        else -> selector.getString(local)?.let(RuleValue::Text) ?: RuleValue.Empty
                    }
                }
                "xpath" -> {
                    val selector = AnalyzeByXPath(body)
                    when {
                        elements -> RuleValue.Items(selector.getElements(local).orEmpty().map { RuleValue.Node(it.toString(), InputKind.Xml) })
                        list -> RuleValue.Items(selector.getStringList(local).map(RuleValue::Text))
                        else -> selector.getString(local)?.let(RuleValue::Text) ?: RuleValue.Empty
                    }
                }
                else -> {
                    val selector = AnalyzeByJSoup(if (input is RuleValue.Node && input.kind == InputKind.Html)
                        input.htmlElement() else body)
                    val expression = if (mode == "css") "@CSS:$local" else local
                    when {
                        elements -> RuleValue.Items(selector.getElements(expression).map { RuleValue.Node(it.outerHtml(), InputKind.Html, it.parent()?.tagName()) })
                        list -> RuleValue.Items(selector.getStringList(expression).map(RuleValue::Text))
                        output == OutputKind.Url -> RuleValue.Text(selector.getString0(expression))
                        else -> selector.getString(expression)?.let(RuleValue::Text) ?: RuleValue.Empty
                    }
                }
            }
        }
    }

    private fun interpolate(text: String, input: RuleValue, root: RuleValue, context: RuleContext,
        location: RuleLocation, budget: RuleBudget, depth: Int, captures: Boolean = true): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            budget.check()
            when {
                text.regionMatches(index, "@get:{", 0, 6, true) -> {
                    val end = parser.balancedEnd(text, index + 5, location, budget)
                    out.append(context.get(text.substring(index + 6, end - 1)))
                    index = end
                }
                text.startsWith("{{", index) -> {
                    val end = parser.balancedEnd(text, index, location, budget)
                    val expression = text.substring(index + 2, end - 2)
                    val at = location.copy(offset = location.offset + index + 2)
                    out.append(if (expression.startsWith('@') || expression.startsWith("$.") || expression.startsWith("$[") || expression.startsWith("//")) {
                        run(expression, root, root, context, OutputKind.Text, at, budget, depth + 1).text()
                    } else script(expression, input, context, at, budget).text())
                    index = end
                }
                captures && input is RuleValue.Captures && text[index] == '$' && text.getOrNull(index + 1)?.isDigit() == true -> {
                    val end = (index + 3).coerceAtMost(text.length).let { if (text.getOrNull(index + 2)?.isDigit() == true) it else index + 2 }
                    out.append(input.groups.getOrElse(text.substring(index + 1, end).toInt()) { "" })
                    index = end
                }
                else -> out.append(text[index++])
            }
            budget.checkSize(out.length, budget.limits.maxOutputChars)
        }
        return out.toString()
    }

    private fun script(text: String, input: RuleValue, context: RuleContext, location: RuleLocation, budget: RuleBudget): RuleValue =
        atStage(RuleStage.Script, location) {
            budget.check()
            (scripts ?: fail(RuleStage.Script, location, 0, "ScriptPortUnavailable"))
                .evaluate(ScriptRequest(text, input, location), context, budget).also { budget.check() }
        }

    private fun absolute(base: String, value: String, emptyUsesBase: Boolean): String = when {
        value.isBlank() -> if (emptyUsesBase) base else ""
        else -> URI(base).resolve(value.trim()).toString()
    }

    private inline fun <T> atStage(stage: RuleStage, location: RuleLocation, block: () -> T): T = try { block() }
        catch (failure: RuleBudgetExceeded) { throw failure }
        catch (failure: RuleFailure) { throw failure }
        catch (failure: RuleScriptFailure) { throw RuleFailure(RuleError(stage, location, failure.code)) }
        catch (failure: Exception) { throw RuleFailure(RuleError(stage, location, failure.javaClass.simpleName)) }

    private fun fail(stage: RuleStage, location: RuleLocation, offset: Int, code: String): Nothing =
        throw RuleFailure(RuleError(stage, location.copy(offset = location.offset + offset), code))
}
