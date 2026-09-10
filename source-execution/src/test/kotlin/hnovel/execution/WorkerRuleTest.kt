package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkerRuleTest {
    @Test fun metadataAndScopedWritesCrossWireWithoutChangingBookIdentity() {
        val book = buildJsonObject { put("name", "Same book"); put("author", "Same author"); put("bookUrl", "/book"); put("wordCount", "10k"); put("kind", "novel,fiction") }
        val chapter = buildJsonObject { put("url", "one,{\"method\":\"POST\"}"); put("baseUrl", "https://fixture.invalid/toc/"); put("title", "One") }
        val task = ExecutionTask.Rule("""@js:
            book.putVariable('token','book');chapter.putVariable('token',null);
            [book.name,book.getKindList().join('|'),chapter.getAbsoluteURL(),java.get('token'),book.id].join(';')
        """, RuleValue.Text("input"), OutputKind.Text, bookId="source-a:book", book=book, chapter=chapter,
            chapterVariables=mapOf("token" to "old"))
        val result = value(run(task))
        assertEquals(RuleValue.Text("Same book;10k|novel|fiction;https://fixture.invalid/toc/one,{\"method\":\"POST\"};book;source-a:book"), result.value)
        assertEquals(mapOf("token" to "book"), result.bookWrites)
        assertEquals(mapOf("token" to null), result.chapterWrites)
        assertEquals("Same book", book.getValue("name").jsonPrimitive.content)
        assertEquals(RuleValue.Text(""), value(run(task.copy(rule="@js:book.getVariable('token')", chapterVariables=emptyMap()))).value)
    }
    private val id = ExecutionAuthority().issue("a", "legado", "1")
    private fun run(task: ExecutionTask.Rule): ExecutionResult {
        val input = ExecutionWire.encode(id, task, ExecutionLimits())
        return ExecutionWire.decodeResult(WorkerMain.executeSerialized(input.toString(Charsets.UTF_8)).toByteArray())
    }
    private fun value(result: ExecutionResult): ExecutedRule {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        return Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
    }

    @Test fun htmlSelectionFeedsRhinoAndJsonObjectResultsFeedLaterRules() {
        val input = RuleValue.Text("<ul><li>one</li><li>two</li></ul>")
        val output = value(run(ExecutionTask.Rule("tag.li@text<js>result.map(function(x){return x.toUpperCase()})</js>", input)))
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("ONE"), RuleValue.Text("TWO"))), output.value)
        assertEquals(RuleValue.Text("ONE"), value(run(ExecutionTask.Rule(
            "<js>({title:'ONE'})</js>$.title", input, OutputKind.Text))).value)
    }

    @Test fun javaVariablesShareRuleContextButDoNotLeakIntoAnotherBook() {
        val task = ExecutionTask.Rule("<js>java.put('title',java.get('title')+'!');result</js>@get:{title}", RuleValue.Text("input"),
            OutputKind.Text, sourceVariables = mapOf("title" to "source"), bookVariables = mapOf("title" to "book"),
            chapterVariables = mapOf("title" to "chapter"))
        assertEquals(ExecutedRule(RuleValue.Text("chapter!"), mapOf("title" to "chapter!")), value(run(task)))
        assertEquals(ExecutedRule(RuleValue.Text("source"), emptyMap()), value(run(task.copy(rule = "@get:{title}",
            bookVariables = emptyMap(), chapterVariables = emptyMap()))))
    }

    @Test fun scriptErrorsKeepStageFieldAndOffsetWithoutExposingCode() {
        val rule = "tag.h1@text<js>return 'secret'</js>"
        val failure = run(ExecutionTask.Rule(rule, RuleValue.Text("<h1>A</h1>"), location = RuleLocation("ruleBookInfo.name", 7)))
        assertEquals(ExecutionResult.Failure(FailureCode.ScriptSyntax,
            RuleError(RuleStage.Script, RuleLocation("ruleBookInfo.name", 7 + rule.indexOf("<js>") + 4), "Syntax")), failure)
        assertFalse(failure.toString().contains("secret"))
    }

    @Test(timeout = 15000) fun realChildWorkerRunsJsonAndXPathRulesWithRhino() {
        val worker = IsolatedExecutor()
        val task = ExecutionTask.Rule("$.items[*].name@js:result.map(function(x){return x+'!'})",
            RuleValue.Node("{\"items\":[{\"name\":\"A\"}]}", InputKind.Json))
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("A!"))), value(worker.execute(id, task)).value)
        assertEquals(RuleValue.Text("A"), value(worker.execute(id, ExecutionTask.Rule("//book/text()",
            RuleValue.Node("<root><book>A</book></root>", InputKind.Xml), OutputKind.Text))).value)
    }
}
