package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MetadataWireTest {
    @Test fun variableResultsMatchTheReferenceAppAndSourceFallbackWorksForSelectors() {
        val id = ExecutionAuthority().issue("a", "legado", "1")
        val source = mutableMapOf<String, String>()
        val bridge = hnovel.rhino.HostBridge { name, args ->
            val key = args[0].jsonPrimitive.content
            when (name) {
                "source.get" -> JsonPrimitive(source[key].orEmpty())
                "source.put" -> JsonPrimitive(args[1].jsonPrimitive.content.also { source[key] = it })
                else -> error("Unexpected host call: $name")
            }
        }
        val code = """
            var values=[java.get('bookName'),java.get('title')];book.name='Renamed';
            values.push(java.get('bookName'),java.get('title'));
            java.put('token','from java');values.push(chapter.getVariable('token'));
            chapter.putVariable('token','from chapter');values.push(java.get('token'));
            java.put('token','');book.putVariable('token','book fallback');values.push(java.get('token'));
            java.put('title','shadow title');values.push(java.get('title'));
            java.put('large',new Array(10001).join('x'));values.push(String(chapter.getVariable('large')).length);
            source.put('fallback','stored');values.push(java.get('fallback'));JSON.stringify(values)
        """.trimIndent()
        fun execute(task: ExecutionTask.Rule): ExecutedRule {
            val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(
                ExecutionWire.encode(id, task, ExecutionLimits()).toString(Charsets.UTF_8), bridge).toByteArray())
            assertTrue(result.toString(), result is ExecutionResult.Success)
            return Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
        }
        val task = ExecutionTask.Rule("@js:$code", RuleValue.Empty, OutputKind.Text,
            book = buildJsonObject { put("name", "Book") }, chapter = buildJsonObject { put("title", "Title") })
        assertEquals(RuleValue.Text("""["Book","Title","Renamed","Title","from java","from chapter","book fallback","Title",10000,"stored"]"""), execute(task).value)
        assertEquals(RuleValue.Text("stored"), execute(task.copy(rule = "@get:{fallback}")).value)
        assertEquals(RuleValue.Text(""), execute(task.copy(rule = "@get:{bookName}", book = buildJsonObject { put("name", "") },
            sourceVariables = mapOf("bookName" to "must not replace empty metadata"))).value)
    }

    @Test fun specialNamesAndVariableWritesUseTheCurrentBookAndChapterAcrossRuleStages() {
        val id = ExecutionAuthority().issue("a", "legado", "1")
        val rule = """<js>
            var names=[java.get('bookName'),java.get('title')];
            book.setName('Renamed');chapter.setTitle('Next title');
            names.push(java.get('bookName'),java.get('title'));
            java.put('token','from java');names.push(chapter.getVariable('token'));
            chapter.putVariable('token','from chapter');names.push(java.get('token'));
            java.put('saved',names.join('|'));result
            </js>{{java.get('saved')}}|@get:{bookName}|@get:{title}|@get:{token}"""
        val task = ExecutionTask.Rule(rule, RuleValue.Text("input"), OutputKind.Text,
            bookId = "book", chapterId = "chapter", sourceVariables = mapOf("token" to "source"),
            book = buildJsonObject { put("name", "Book") }, chapter = buildJsonObject { put("title", "Title") },
            chapterVariables = mapOf("bookName" to "shadow book", "title" to "shadow title"))
        val wire = ExecutionWire.encode(id, task, ExecutionLimits())
        val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray())
        assertTrue(result.toString(), result is ExecutionResult.Success)
        val data = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
        assertEquals(RuleValue.Text("Book|Title|Renamed|Next title|from java|from chapter|Renamed|Next title|from chapter"), data.value)
        assertEquals("from chapter", data.chapterWrites["token"])
        assertTrue(data.writes.isEmpty())
    }

    @Test fun serializedVariablesAreVisibleBeforeAndAfterScriptStages() {
        val id=ExecutionAuthority().issue("a","legado","1")
        for (rule in listOf("@get:{token}","<js>book.variable='{\"token\":\"changed\"}';result</js>@get:{token}")) {
            val task=ExecutionTask.Rule(rule,RuleValue.Text("input"),OutputKind.Text,
                book=buildJsonObject { put("variable","{\"token\":\"initial\"}") })
            val wire=ExecutionWire.encode(id,task,ExecutionLimits())
            val result=ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray()) as ExecutionResult.Success
            val data=Json.decodeFromString(ExecutedRule.serializer(),result.output)
            assertEquals(RuleValue.Text(if(rule.startsWith("<js>")) "changed" else "initial"),data.value)
        }
    }
    @Test fun metadataAndBigWritesSurviveScriptStagesAndWireWithoutImplicitPersistence() {
        val id=ExecutionAuthority().issue("a","legado","1")
        val task=ExecutionTask.Rule("""<js>
            book.setName('Updated');chapter.setTitle('龍');
            book.putVariable('big',new Array(10001).join('x'));result
            </js><js>[book.name,chapter.getDisplayTitle(),book.getVariable('big').length].join('|')</js>""",
            RuleValue.Text("input"),OutputKind.Text,book=buildJsonObject { put("name","Original") },chineseConverter=1)
        val wire=ExecutionWire.encode(id,task,ExecutionLimits())
        val result=ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray())
        assertTrue(result.toString(),result is ExecutionResult.Success)
        val data=Json.decodeFromString(ExecutedRule.serializer(),(result as ExecutionResult.Success).output)
        assertEquals(RuleValue.Text("Updated|龙|10000"),data.value)
        assertEquals("Updated",data.book!!.getValue("name").jsonPrimitive.content)
        assertEquals("龍",data.chapter!!.getValue("title").jsonPrimitive.content)
        assertEquals(10000,data.bookBigWrites.getValue("big")!!.length)
        assertTrue(data.bookWrites.containsKey("big"));assertNull(data.bookWrites["big"])
        assertEquals("Original",task.book.getValue("name").jsonPrimitive.content)
    }
}
