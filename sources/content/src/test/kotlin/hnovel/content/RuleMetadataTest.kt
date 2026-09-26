package hnovel.content

import hnovel.execution.ExecutionTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleMetadataTest {
    @Test fun absentAndBlankKindRulesKeepEarlierMetadataWithoutExecutingAnEmptyTask(): Unit = runBlocking {
        for (kind in listOf(null, "", " \t\n")) for (discovery in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val kindTasks = mutableListOf<ExecutionTask.Rule>()
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule && task.location.field.endsWith(".kind")) kindTasks += task
            }
            fixture.source(customize = { raw ->
                val list = JsonObject(raw.getValue("ruleSearch").jsonObject + mapOf(
                    "name" to JsonPrimitive("h2@text@js:book.kind='inherited,tag';result")
                ) + if (kind == null) emptyMap() else mapOf("kind" to JsonPrimitive(kind)))
                val information = JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                    if (kind == null) emptyMap() else mapOf("kind" to JsonPrimitive(kind)))
                JsonObject(raw + mapOf("ruleSearch" to list, "ruleExplore" to list,
                    "exploreUrl" to JsonPrimitive("/search"), "ruleBookInfo" to information))
            }).use { source ->
                val book = if (discovery) source.openDiscovery("metadata").page("/search", 1, emptyMap()).single()
                    else source.search("title").single()
                assertEquals(listOf("inherited", "tag"), book.tags)
                assertEquals(listOf("inherited", "tag"), source.information(book.id).tags)
                assertEquals("Missing metadata rules must not occupy the isolated worker", emptyList<ExecutionTask.Rule>(), kindTasks)
            }
        }
    }

    @Test fun configuredKindStillReturnsMultipleTagsAndPersistsItsWrites(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to
                JsonObject(raw.getValue("ruleSearch").jsonObject + ("kind" to
                    JsonPrimitive("@js:book.putVariable('kindSeen','yes');['fantasy','novel']"))))) }).use { source ->
                val book = source.search("title").single()
                assertEquals(listOf("fantasy", "novel"), book.tags)
                assertEquals("yes", book.state.variables["kindSeen"])
                val information = source.information(book.id)
                assertEquals(book.tags, information.tags)
                assertEquals("yes", information.state.variables["kindSeen"])
            }
        }
    }

    @Test fun configuredKindWithEmptyOutputStillAppliesItsMetadataAndVariableWrites(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to
                JsonObject(raw.getValue("ruleSearch").jsonObject + ("kind" to
                    JsonPrimitive("@js:book.kind='from-script';book.putVariable('kindSeen','yes');''"))))) }).use { source ->
                val book = source.search("title").single()
                assertEquals(listOf("from-script"), book.tags)
                assertEquals("yes", book.state.variables["kindSeen"])
            }
        }
    }
}
