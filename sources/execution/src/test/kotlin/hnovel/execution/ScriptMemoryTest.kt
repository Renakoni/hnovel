package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptMemoryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun limitsRejectOnlyTheOverflowingWrite() {
        val memory = ScriptMemory(maxEntries = 2, maxChars = 16)
        memory.put("a", "1234")
        memory.put("b", "1234")
        assertTrue(runCatching { memory.put("c", "") }.isFailure)
        assertTrue(runCatching { memory.put("a", "12345678901") }.isFailure)
        assertTrue(runCatching { memory.put("k".repeat(257), "") }.isFailure)
        assertEquals("1234", memory.get("a"))
        memory.put("a", "123456789")
        memory.delete("b")
        memory.put("c", "")
        assertEquals(listOf("123456789", null, ""), listOf(memory.get("a"), memory.get("b"), memory.get("c")))
    }

    @Test fun scriptsRoundTripTextWithoutStorageOrRequestBudget() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1")
        SourceBroker(directory.root.toPath()).use { broker ->
            val session = broker.open(SourceScope("default", "a", "legado"), listOf(NetworkGrant("https://source.invalid/")))
            val memory = ScriptMemory(maxChars = 64)
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 0), memory = memory).use { bridge ->
                assertEquals(ExecutionResult.Success("[\"[1,2]\",\"7\",null,null,null]"), script(id, bridge, """
                    cache.putMemory('set', JSON.stringify([1,2])); cache.putMemory('number', 7);
                    cache.putMemory('removed', 'x'); cache.deleteMemory('removed'); cache.putMemory('nulled', 'x'); cache.putMemory('nulled', null);
                    [cache.getFromMemory('set'), cache.getFromMemory('number'), cache.getFromMemory('removed'), cache.getFromMemory('nulled'),
                        cache.getFromMemory('missing')]
                """.trimIndent()))
                assertEquals(ExecutionResult.Success("[\"caught\",\"caught\",\"[1,2]\"]"), script(id, bridge, """
                    function attempt(f){try{f();return 'stored'}catch(e){return 'caught'}}
                    [attempt(function(){cache.putMemory('object',{a:1})}), attempt(function(){cache.putMemory('large',Array(61).join('x'))}),
                        cache.getFromMemory('set')]
                """.trimIndent()))
                assertFalse(bridge.requestLimitExceeded)
            }
            SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { bridge ->
                assertEquals(ExecutionResult.Success("[null,null]"), script(id, bridge, "[cache.get('set'), cache.getFromMemory('set')]"))
            }
            assertEquals("[1,2]", memory.get("set"))
        }
    }

    private fun script(id: ExecutionIdentity, bridge: SourceExecutionBroker, code: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(code), bridge.limits)
        val output = WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args -> runBlocking { bridge.call(name, args) } })
        return ExecutionWire.decodeResult(output.toByteArray())
    }
}
