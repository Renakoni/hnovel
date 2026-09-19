package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptDiscoveryTest {
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host operation expected") })
    private fun state(name: String = "a", interactive: Boolean = true) = ScriptDiscovery(buildJsonObject {
        put("values", buildJsonObject { put("name", name) }); put("interactive", interactive)
    })
    private fun frame(state: ScriptDiscovery) = ScriptFrame("source-a", "legado", discovery = state)

    @Test fun currentSourceObjectIsNormalizedBeforeSerializingSearchAndNullLoginUpdatesAreValid() {
        val state = state()
        assertEquals(ScriptResult.Success("true"), engine.evaluate(
            "java.searchBook('one',source);java.searchBook('two',java.getSource());java.upLoginData(null);true", frame(state)))
        val actions = state.snapshot.getValue("actions").jsonArray
        assertEquals(listOf("one", "two"), actions.map { it.jsonObject.getValue("args").jsonArray.single().jsonPrimitive.content })
        assertEquals("a", state.snapshot.getValue("values").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun libraryHelpersUseExplicitInvocationArgumentsAndRetainedActionsCannotCrossInvocations() {
        ScriptLibrary("source-a", "legado", "var held={};function update(values,host){values.note=values.name;host.refreshExplore();}").use { library ->
            val first = state("first")
            assertEquals(ScriptResult.Success("true"), engine.evaluate(
                "update(infoMap,java);held.refresh=java.refreshExplore;true", frame(first), library))
            val second = state("second")
            assertEquals(ScriptResult.Success("\"invalid discovery action: refreshExplore\""), engine.evaluate(
                "update(infoMap,java);try{held.refresh()}catch(e){e.message}", frame(second), library))
            assertEquals("first", first.snapshot.getValue("values").jsonObject.getValue("note").jsonPrimitive.content)
            assertEquals("second", second.snapshot.getValue("values").jsonObject.getValue("note").jsonPrimitive.content)
            assertEquals(1, second.snapshot.getValue("actions").jsonArray.size)
        }
    }

    @Test fun noninteractiveOrExcessiveActionsFailWithoutPublishingPartialDrafts() {
        for ((interactive, script) in listOf(false to "java.refreshExplore()", true to "for(var i=0;i<17;i++)java.refreshExplore()")) {
            val state = state(interactive = interactive)
            val initial = state.snapshot
            assertTrue(engine.evaluate("infoMap.name='changed';$script", frame(state)) is ScriptResult.Failure)
            assertEquals(initial, state.snapshot)
        }
    }

    @Test fun discoveryScriptsAndSnapshotsUseExistingInstructionAndOutputBudgets() {
        val bounded = RhinoScriptEngine(HostBridge { _, _ -> JsonNull }, ScriptLimits(instructionLimit = 1000, maxBridgeChars = 256))
        val state = state()
        val initial = state.snapshot
        assertEquals(FailureCode.Timeout, (bounded.evaluate("while(true){}", frame(state)) as ScriptResult.Failure).code)
        assertEquals(FailureCode.ResultTooLarge, (bounded.evaluate("infoMap.name='x'.repeat(300);true", frame(state)) as ScriptResult.Failure).code)
        assertEquals(initial, state.snapshot)
    }
}
