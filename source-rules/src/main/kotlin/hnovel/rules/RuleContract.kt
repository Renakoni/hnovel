package hnovel.rules

/** Safe intermediate values; no host/client, DOM object, or scripting engine instance crosses the port. */
sealed interface RuleValue {
    data class Text(val value: String) : RuleValue
    data class Items(val values: List<RuleValue>) : RuleValue
    data class Node(val content: String, val kind: InputKind) : RuleValue
    data class Captures(val groups: List<String>) : RuleValue
    data object Empty : RuleValue
}

enum class InputKind { Auto, Html, Json, Xml }
enum class OutputKind { Text, TextList, Url, UrlList, Element, Elements }
enum class RuleStage { Parse, Select, Replace, Script, Budget }
data class RuleLocation(val field: String, val offset: Int = 0)
data class RuleError(val stage: RuleStage, val location: RuleLocation, val code: String)
sealed interface RuleResult {
    data class Success(val value: RuleValue) : RuleResult
    data class Failure(val error: RuleError) : RuleResult
}

/** Owned by one request/book evaluation. Inherited values are snapshots; puts are request-local. */
class RuleContext(
    val sourceId: String,
    val bookId: String? = null,
    val chapterId: String? = null,
    val baseUrl: String = "",
    sourceVariables: Map<String, String> = emptyMap(),
    bookVariables: Map<String, String> = emptyMap(),
    chapterVariables: Map<String, String> = emptyMap(),
) {
    private val inherited = listOf(chapterVariables.toMap(), bookVariables.toMap(), sourceVariables.toMap())
    private val values = linkedMapOf<String, String>()
    fun get(key: String): String = values[key]?.takeIf { it.isNotEmpty() }
        ?: inherited.firstNotNullOfOrNull { it[key]?.takeIf(String::isNotEmpty) }.orEmpty()
    fun put(key: String, value: String): String { values[key] = value; return value }
    fun writes(): Map<String, String> = values.toMap()
}

data class ScriptRequest(val script: String, val input: RuleValue, val location: RuleLocation)
fun interface RuleScriptPort {
    fun evaluate(request: ScriptRequest, context: RuleContext, budget: RuleBudget): RuleValue
}

data class RuleLimits(val maxRuleChars: Int = 65536, val maxInputChars: Int = 2_000_000,
    val maxOutputChars: Int = 2_000_000, val maxDepth: Int = 64, val maxSteps: Long = 2_000_000,
    val timeoutMillis: Long = 2000) {
    init { require(maxRuleChars > 0 && maxInputChars > 0 && maxOutputChars > 0 && maxDepth > 0 && maxSteps > 0 && timeoutMillis > 0) }
}

class RuleBudget(val limits: RuleLimits = RuleLimits()) {
    private val started = System.nanoTime()
    private var steps = 0L
    fun check(cost: Long = 1) {
        steps += cost
        if (steps > limits.maxSteps || Thread.currentThread().isInterrupted ||
            (System.nanoTime() - started) / 1_000_000 >= limits.timeoutMillis) throw RuleBudgetExceeded()
    }
    fun checkSize(size: Int, max: Int) { check(); if (size > max) throw RuleBudgetExceeded() }
    internal fun checkValue(value: RuleValue, max: Int): Long {
        val pending = ArrayDeque<RuleValue>()
        pending.add(value)
        var size = 0L
        while (pending.isNotEmpty()) {
            check()
            when (val next = pending.removeLast()) {
                is RuleValue.Text -> size += next.value.length
                is RuleValue.Node -> size += next.content.length
                is RuleValue.Captures -> size += next.groups.sumOf { it.length.toLong() + 1 }
                is RuleValue.Items -> { size += next.values.size; pending.addAll(next.values) }
                RuleValue.Empty -> Unit
            }
            if (size > max) throw RuleBudgetExceeded()
        }
        return size
    }
}

class RuleBudgetExceeded : RuntimeException("Rule execution budget exceeded")
internal class RuleFailure(val error: RuleError) : RuntimeException(error.code)

internal fun RuleValue.text(): String = when (this) {
    is RuleValue.Text -> value
    is RuleValue.Node -> content
    is RuleValue.Captures -> groups.joinToString("\n")
    is RuleValue.Items -> values.joinToString("\n") { it.text() }
    RuleValue.Empty -> ""
}

internal fun RuleValue.items(): List<RuleValue> = when (this) {
    is RuleValue.Items -> values
    RuleValue.Empty -> emptyList()
    else -> listOf(this)
}
