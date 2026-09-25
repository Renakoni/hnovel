package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkerBookOverviewsTest {
    private val identity = ExecutionIdentity("fixture", "legado", "1", "overview")
    private fun run(task: ExecutionTask.BookOverviews, bytes: Int = 4 * 1024 * 1024): ExecutionResult =
        Json.decodeFromString(ExecutionResult.serializer(), WorkerMain.executeSerialized(
            ExecutionWire.encode(identity, task, ExecutionLimits(timeoutMillis = 5000, maxOutputBytes = bytes)).toString(Charsets.UTF_8)))
    private fun values(result: ExecutionResult): List<List<String>> {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        val rows = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Items
        return rows.values.map { row -> (row as RuleValue.Items).values.map { (it as RuleValue.Text).value } }
    }
    private fun html(body: String) = RuleValue.Node(body, InputKind.Html, "ul")

    @Test fun selectorsKeepFallbacksHtmlEntitiesAndAbsoluteUrls() {
        val task = ExecutionTask.BookOverviews(listOf(
            html("<li><a href='../one?q=1&amp;x=2'>A &amp; B</a></li>"),
            html("<li><a href='/two'>Second</a></li>")), ".missing@text||a@text", "a@href", "https://example.test/list/page")
        assertEquals(listOf(listOf("A & B", "https://example.test/one?q=1&x=2"),
            listOf("Second", "https://example.test/two")), values(run(task)))
    }

    @Test fun jsonRowsAndListMetadataFallbackRemainIndependent() {
        val task = ExecutionTask.BookOverviews(listOf(
            RuleValue.Node("""{"name":"First","url":"/one"}""", InputKind.Json),
            RuleValue.Node("""{"name":"","url":"/two"}""", InputKind.Json)),
            "name", "url", "https://example.test/list", fallbackTitle = "Fallback")
        assertEquals(listOf(listOf("First", "https://example.test/one"),
            listOf("Fallback", "https://example.test/two")), values(run(task)))
    }

    @Test fun emptyTitlesSkipInvalidUrlRulesAndErrorsRetainTheirField() {
        val task = ExecutionTask.BookOverviews(listOf(html("<li><a></a></li>")), "a@text", "[", "https://example.test/")
        assertEquals(listOf(listOf("", "")), values(run(task)))
        val failed = run(task.copy(inputs = listOf(html("<li><a>Book</a></li>")))) as ExecutionResult.Failure
        assertEquals("ruleExplore.bookUrl", failed.ruleError?.location?.field)
    }

    @Test fun batchesCannotRunScriptsTemplatesOrVariableOperations() {
        val task = ExecutionTask.BookOverviews(listOf(html("<li>A</li>")), "text", "", "https://example.test/")
        for (rule in listOf("@js:java.put('key','value')", "<js>result</js>", "{{book.name}}", "@put:{x:'text'}text", "@get:{x}")) {
            assertEquals(rule, ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(nameRule = rule)))
            assertEquals(rule, ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(urlRule = rule)))
        }
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = emptyList())))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = List(9) { task.inputs[0] })))
    }

    @Test fun aBatchCanExceedOneFieldsSizeWhileKeepingIndividualFieldsBounded() {
        val title = "正文".repeat(10000)
        val task = ExecutionTask.BookOverviews(List(8) { html("<li>$title</li>") }, "text", "", "https://example.test/")
        assertEquals(List(8) { listOf(title, "") }, values(run(task)))
        assertTrue(run(task.copy(inputs = listOf(html("<li>${"A".repeat(200000)}</li>")))) is ExecutionResult.Failure)
        assertTrue(run(task, bytes = 64) is ExecutionResult.Failure)
    }
}
