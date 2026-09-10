package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ScriptExecutionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun deeplyNestedWorkerPayloadIsRejectedBeforeHostParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgeWire.arguments(("[".repeat(10000) + "]".repeat(10000)).toByteArray())
        }
        val text = JsonArray(listOf(JsonPrimitive("[".repeat(100) + "\\\"}"), JsonPrimitive(7)))
        assertEquals(text, JsonArray(BridgeWire.arguments(text.toString().toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionWire.decodeResult(("{\"output\":" + "[".repeat(10000) + "]".repeat(10000) + "}").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { ExecutionWire.decodeResult(ByteArray(BridgeWire.MAX_BYTES + 1)) }
        val result = ExecutionResult.Success("[".repeat(100) + "\\\"}")
        assertEquals(result, ExecutionWire.decodeResult(ExecutionWire.encodeResult(result)))
    }

    @Test(timeout = 10000) fun realChildWorkerRunsRhinoAndTerminatesUnboundedScript() {
        val authority = ExecutionAuthority()
        val id = authority.issue("source-a", "legado", "1")
        val worker = IsolatedExecutor(authority = authority)
        assertEquals(ExecutionResult.Success("[\"source-a\",42]"), worker.execute(id,
            ExecutionTask.Script("[source.id,result*2]", JsonPrimitive(21))))
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), worker.execute(id, ExecutionTask.Script("while(true){}")))
        assertEquals(ExecutionResult.Success("1"), worker.execute(id, ExecutionTask.Script("1")))
    }

    @Test fun scriptUsesRealBrokerForAjaxAndScopedStorage() = runBlocking<Unit> {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), server.url("/").toString()).use { bridge ->
                    server.enqueue(MockResponse().setBody("chapter text"))
                    val result = runScript(id, bridge, "source.put('chapter',java.ajax('/chapter')); source.get('chapter')")
                    assertEquals(ExecutionResult.Success("\"chapter text\""), result)
                    assertEquals("/chapter", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), runScript(id, bridge, "java.ajax('https://not-granted.invalid/')"))
                }
                val b = authority.issue("b", "legado", "1", "fixture")
                val other = sessions.open(SourceScope("fixture", "b", "legado"), emptyList())
                SourceExecutionBroker(b, authority, other, ExecutionLimits()).use { bridge ->
                    assertEquals(ExecutionResult.Success("\"\""), runScript(b, bridge, "source.get('chapter')"))
                }
                assertThrows(IllegalArgumentException::class.java) { SourceExecutionBroker(id, authority, other, ExecutionLimits()) }
            }
        }
    }

    @Test fun revokedAndExhaustedTicketsCannotCommitStorage() = runBlocking {
        val authority = ExecutionAuthority()
        SourceBroker(directory.root.toPath()).use { sessions ->
            val id = authority.issue("a", "legado", "1", "fixture")
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1)).use { bridge ->
                bridge.call("source.put", listOf(JsonPrimitive("key"), JsonPrimitive("before")))
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), runScript(id, bridge, "source.put('key','exhausted')"))
                authority.revoke(id)
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), runScript(id, bridge, "source.put('key','revoked')"))
                assertEquals(StorageResult.Value("before"), session.read(StorageRequest(StorageArea.Config, "value:key")))
            }
        }
    }

    @Test fun revokeBeforeResponseCommitDoesNotSaveCookies() = runBlocking {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val bridge = SourceExecutionBroker(id, authority, session, ExecutionLimits(), server.url("/").toString())
                server.enqueue(MockResponse().setBody("late").addHeader("Set-Cookie", "auth=late; Path=/").setBodyDelay(500, TimeUnit.MILLISECONDS))
                val pending = async(Dispatchers.IO) { runCatching { bridge.call("java.ajax", listOf(JsonPrimitive("/login"))) } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                authority.revoke(id)
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                bridge.close()
                server.enqueue(MockResponse().setBody("next"))
                session.execute(BrokerRequest("next", server.url("/next").toString()))
                assertNull(server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            }
        }
    }

    private fun runScript(id: ExecutionIdentity, bridge: SourceExecutionBroker, script: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(script), bridge.limits)
        val output = WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args ->
            runBlocking { bridge.call(name, args) }
        })
        return ExecutionWire.decodeResult(output.toByteArray(Charsets.UTF_8))
    }
}
