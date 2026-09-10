package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MetadataWireTest {
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
