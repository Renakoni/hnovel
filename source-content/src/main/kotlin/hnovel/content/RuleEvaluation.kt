package hnovel.content

import hnovel.execution.*
import hnovel.network.SourceSession
import hnovel.rules.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** One book/row evaluation. State is copied between rows and committed only after its stage succeeds. */
internal class RuleEvaluation(private val identity: ExecutionIdentity, private val authority: ExecutionAuthority,
    private val session: SourceSession, private val runner: RuleTaskRunner, private val library: String?,
    var bookId: String? = null, var chapterId: String? = null, var book: ScriptState = ScriptState(),
    var chapter: ScriptState = ScriptState(), var baseUrl: String, val keyword: String = "", var page: Int = 1,
    private val calls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()) {
    private val limits = ExecutionLimits(timeoutMillis = 5000, maxOutputBytes = 196608)

    fun fork(bookId: String? = this.bookId, chapterId: String? = this.chapterId) =
        RuleEvaluation(identity, authority, session, runner, library, bookId, chapterId, book.copy(), chapter.copy(), baseUrl, keyword, page, calls)

    suspend fun value(rule: String, input: RuleValue, field: String, output: OutputKind = OutputKind.Text,
        unescape: Boolean = true): RuleValue {
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        if (calls.incrementAndGet() > 4096) throw SourceContentException(ContentError.Limit, field)
        val task = ExecutionTask.Rule(rule, input, output, RuleLocation(field), bookId, chapterId,
            keyword, page, baseUrl, library, book.inherited + chapter.inherited, book.variables,
            chapter.variables, book.metadata, chapter.metadata, book.bigVariables, chapter.bigVariables,
            unescapeHtml = unescape)
        val result = SourceExecutionBroker(identity, authority, session, limits, baseUrl, keyword, page).use {
            runner.execute(identity, task, limits, it)
        }
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        when (result) {
            is ExecutionResult.Failure -> throw SourceContentException(when (result.code) {
                FailureCode.Revoked, FailureCode.InvalidIdentity -> ContentError.Unavailable
                FailureCode.Timeout, FailureCode.InputLimit, FailureCode.OutputLimit -> ContentError.Limit
                FailureCode.BridgeDenied -> ContentError.PermissionDenied
                else -> ContentError.InvalidRule
            }, field)
            is ExecutionResult.Success -> {
                val executed = Json.decodeFromString(ExecutedRule.serializer(), result.output)
                book = book.copy(metadata = executed.book ?: book.metadata,
                    variables = applyWrites(book.variables, executed.bookWrites),
                    bigVariables = applyWrites(book.bigVariables, executed.bookBigWrites),
                    inherited = if (chapterId == null) book.inherited + executed.writes else book.inherited)
                chapter = chapter.copy(metadata = executed.chapter ?: chapter.metadata,
                    variables = applyWrites(chapter.variables, executed.chapterWrites),
                    bigVariables = applyWrites(chapter.bigVariables, executed.chapterBigWrites),
                    inherited = if (chapterId != null) chapter.inherited + executed.writes else chapter.inherited)
                return executed.value
            }
        }
    }

    suspend fun text(rule: String, input: RuleValue, field: String, unescape: Boolean = true): String =
        if (rule.isBlank()) "" else value(rule, input, field, unescape = unescape).text()

    suspend fun script(code: String, input: RuleValue, field: String): RuleValue =
        value(if (code.startsWith("@js:", true) || code.startsWith("<js>", true)) code else "@js:$code", input, field, OutputKind.Element)

    fun bookField(name: String, value: String) { book = book.copy(metadata = JsonObject(book.metadata + (name to JsonPrimitive(value)))) }
    fun chapterField(name: String, value: JsonElement) { chapter = chapter.copy(metadata = JsonObject(chapter.metadata + (name to value))) }

    private fun applyWrites(before: Map<String, String>, writes: Map<String, String?>) = before.toMutableMap().apply {
        writes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }.toMap()
}

internal fun RuleValue.items(): List<RuleValue> = when (this) {
    is RuleValue.Items -> values
    RuleValue.Empty -> emptyList()
    else -> listOf(this)
}
internal fun RuleValue.text(): String = when (this) {
    is RuleValue.Text -> value
    is RuleValue.Node -> content
    is RuleValue.Items -> values.joinToString("\n") { it.text() }
    is RuleValue.Captures -> groups.joinToString("\n")
    RuleValue.Empty -> ""
}

internal fun scriptBody(code: String): String = code.trim().let {
    when {
        it.startsWith("@js:", true) -> it.substring(4)
        it.startsWith("<js>", true) && it.endsWith("</js>", true) -> it.substring(4, it.length - 5)
        else -> it
    }
}
