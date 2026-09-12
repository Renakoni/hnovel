package hnovel.rules

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeFilter.FilterResult
import org.jsoup.select.NodeTraversor

/** Trusted, worker-only HTML conversion. No source script, library or DOM facade participates. */
object ContentMarkup {
    private const val MAX_NODES = 16_384
    private val blocks = setOf("body", "div", "p", "li", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "tr", "pre")
    private val ignored = setOf("script", "style", "noscript")

    fun evaluate(html: String, location: RuleLocation = RuleLocation("ruleContent.parts"),
        budget: RuleBudget = RuleBudget()): RuleResult = try {
        fun limit(code: String): Nothing = throw RuleFailure(RuleError(RuleStage.Budget, location, code))
        budget.check()
        if (html.length > budget.limits.maxInputChars) limit("MarkupInputLimit")
        // Parsing also stays behind the worker's hard deadline; the input bound precedes allocation.
        val root = Jsoup.parse(html).body()
        budget.check()
        val parts = mutableListOf<RuleValue>()
        val text = StringBuilder()
        var outputChars = 0
        var nodes = 0

        fun append(value: String) {
            budget.check(value.length.toLong())
            if (value.length > budget.limits.maxOutputChars - outputChars - text.length) limit("MarkupOutputLimit")
            text.append(value)
        }
        fun part(name: String, value: String) {
            budget.check()
            val json = buildJsonObject { put(name, value) }.toString()
            if (json.length > budget.limits.maxOutputChars - outputChars) limit("MarkupOutputLimit")
            outputChars += json.length
            parts += RuleValue.Node(json, InputKind.Json)
        }
        fun flush() {
            // Match the previous script: split LF/CRLF (not an internal lone CR), then ECMAScript trim.
            for (line in text.splitToSequence('\n')) {
                budget.check()
                val trimmed = line.trim(::whitespace)
                if (trimmed.isNotEmpty()) part("text", trimmed)
            }
            text.setLength(0)
        }

        // Jsoup walks iteratively; deeply nested input never consumes a recursive Kotlin/JS stack.
        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): FilterResult {
                budget.check()
                if (++nodes > MAX_NODES) limit("MarkupNodeLimit")
                if (depth > budget.limits.maxDepth) limit("MarkupDepthLimit")
                val name = node.nodeName()
                when {
                    node is TextNode -> append(node.wholeText)
                    name in ignored -> return FilterResult.SKIP_CHILDREN
                    name == "img" -> {
                        flush()
                        val src = node.attr("src")
                        if (src.isNotEmpty()) part("image", src)
                    }
                    name == "br" -> append("\n")
                    name in blocks -> append("\n")
                }
                return FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): FilterResult {
                budget.check()
                if (node.nodeName() in blocks) append("\n")
                return FilterResult.CONTINUE
            }
        }, root)
        flush()
        RuleResult.Success(RuleValue.Items(parts))
    } catch (failure: RuleFailure) {
        RuleResult.Failure(failure.error)
    } catch (_: RuleBudgetExceeded) {
        RuleResult.Failure(RuleError(RuleStage.Budget, location, "BudgetExceeded"))
    } catch (_: StackOverflowError) {
        RuleResult.Failure(RuleError(RuleStage.Budget, location, "StackLimitExceeded"))
    } catch (_: Exception) {
        RuleResult.Failure(RuleError(RuleStage.Select, location, "MarkupConversionFailed"))
    }

    private fun whitespace(char: Char) = char in "\t\n\u000B\u000C\r\u2028\u2029\uFEFF" ||
        Character.getType(char) == Character.SPACE_SEPARATOR.toInt()
}
