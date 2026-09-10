package hnovel.rules

data class RuleStep(val text: String, val offset: Int, val script: Boolean = false)
data class RulePlan(val steps: List<RuleStep>)

/** Balanced scanning preserves script/template payloads and escaped delimiters. */
class RuleParser {
    fun parse(rule: String, location: RuleLocation, budget: RuleBudget): RulePlan {
        budget.checkSize(rule.length, budget.limits.maxRuleChars)
        val steps = mutableListOf<RuleStep>()
        var start = 0
        var index = 0
        var replacement = false
        while (index < rule.length) {
            budget.check()
            when {
                rule.regionMatches(index, "@js:", 0, 4, true) -> {
                    if (index > start) steps.add(RuleStep(rule.substring(start, index), start))
                    steps.add(RuleStep(rule.substring(index + 4), index + 4, true))
                    return RulePlan(steps)
                }
                rule.regionMatches(index, "<js>", 0, 4, true) -> {
                    if (index > start) steps.add(RuleStep(rule.substring(start, index), start))
                    val end = scriptEnd(rule, index + 4, location, budget)
                    steps.add(RuleStep(rule.substring(index + 4, end), index + 4, true))
                    index = end + 5
                    start = index
                    replacement = false
                }
                rule.startsWith("##", index) -> { replacement = true; index += 2 }
                replacement -> index += if (rule[index] == '\\') 2 else 1
                else -> index = skipUnit(rule, index, location, budget)
            }
        }
        if (start < rule.length) steps.add(RuleStep(rule.substring(start), start))
        return RulePlan(steps)
    }

    fun split(text: String, delimiters: List<String>, location: RuleLocation, budget: RuleBudget): Pair<String?, List<RuleStep>> {
        require(delimiters.isNotEmpty() && delimiters.none { it.isEmpty() })
        budget.checkSize(text.length, budget.limits.maxRuleChars)
        var index = 0
        var start = 0
        var chosen: String? = null
        val result = mutableListOf<RuleStep>()
        while (index < text.length) {
            budget.check()
            val delimiter = (chosen?.let(::listOf) ?: delimiters).firstOrNull { text.startsWith(it, index) }
            if (delimiter != null) {
                chosen = delimiter
                result.add(RuleStep(text.substring(start, index), start))
                index += delimiter.length
                start = index
            } else if (chosen == "##") index += if (text[index] == '\\') 2 else 1
            else index = skipUnit(text, index, location, budget)
        }
        result.add(RuleStep(text.substring(start), start))
        return chosen to result
    }

    fun balancedEnd(text: String, start: Int, location: RuleLocation, budget: RuleBudget): Int =
        skipUnit(text, start, location, budget)

    private fun skipUnit(text: String, start: Int, location: RuleLocation, budget: RuleBudget, depth: Int = 0): Int {
        budget.check()
        if (depth > budget.limits.maxDepth) throw RuleBudgetExceeded()
        val char = text[start]
        if (char == '\\') return (start + 2).coerceAtMost(text.length)
        if (char in "\"'`") {
            var index = start + 1
            while (index < text.length) {
                budget.check()
                if (text[index] == char) return index + 1
                index += if (text[index] == '\\') 2 else 1
            }
            fail(location, start, "UnclosedQuote")
        }
        val close = when (char) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> return start + 1 }
        var index = start + 1
        while (index < text.length) {
            if (text[index] == close) return index + 1
            if (text[index] in ")]}") fail(location, index, "MismatchedDelimiter")
            index = skipUnit(text, index, location, budget, depth + 1)
        }
        fail(location, start, "UnclosedDelimiter")
    }

    private fun scriptEnd(text: String, start: Int, location: RuleLocation, budget: RuleBudget): Int {
        var index = start
        var previous = '='
        while (index < text.length) {
            budget.check()
            when {
                text.regionMatches(index, "</js>", 0, 5, true) -> return index
                text[index] in "\"'`" -> index = skipUnit(text, index, location, budget)
                text.startsWith("//", index) -> index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                text.startsWith("/*", index) -> index = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2)
                    ?: fail(location, index, "UnclosedComment")
                text[index] == '/' && previous in "=([{,:;!?&|" -> {
                    index++
                    var inClass = false
                    while (index < text.length) {
                        budget.check()
                        val char = text[index++]
                        if (char == '\\') { index++; continue }
                        if (char == '[') inClass = true
                        if (char == ']') inClass = false
                        if (char == '/' && !inClass) break
                    }
                    previous = 'x'
                }
                else -> { if (!text[index].isWhitespace()) previous = text[index]; index++ }
            }
        }
        fail(location, start, "UnclosedScript")
    }

    private fun regexCanStart(text: String, index: Int, previous: Char): Boolean {
        if (previous in "=([{,:;!?&|") return true
        if (previous == ')' || previous == '>') return true
        var end = index - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        val wordEnd = end + 1
        while (end >= 0 && text[end].isLetter()) end--
        return text.substring(end + 1, wordEnd) in setOf("return", "throw", "case", "delete", "void", "typeof", "instanceof", "in", "of")
    }

    private fun fail(location: RuleLocation, offset: Int, code: String): Nothing =
        throw RuleFailure(RuleError(RuleStage.Parse, location.copy(offset = location.offset + offset), code))
}




