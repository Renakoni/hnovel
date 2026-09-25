package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import hnovel.rules.ScriptArgumentType.Null
import hnovel.rules.ScriptArgumentType.String
import hnovel.rules.ScriptHostCall
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class RequestRefetchBrokerTest {
    @get:Rule val directory = TemporaryFolder()
    private val args = listOf(JsonNull, JsonNull)

    @Test fun missingContextInvalidArgumentsAndExhaustedBudgetCannotSendRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val authority = ExecutionAuthority()
            val id = authority.issue("a", "legado", "1")
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("default", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getStrResponse", 2, listOf(Null, Null))), script(id, bridge, "java.getStrResponse(null,null)"))
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), currentRequest = BrokerRequest("current", server.url("/").toString())).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getStrResponse", 2, listOf(String, Null))), script(id, bridge, "java.getStrResponse('replacement URL',null)"))
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), currentRequest = BrokerRequest("current", server.url("/").toString(),
                    browser = BrowserOptions(interactive = true))).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getStrResponse", 2, listOf(Null, Null))), script(id, bridge, "java.getStrResponse(null,null)"))
                    assertTrue(bridge.interactionRequired)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 0), currentRequest = BrokerRequest("current", server.url("/").toString())).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getStrResponse", 2, listOf(Null, Null))), script(id, bridge, "java.getStrResponse(null,null)"))
                    assertTrue(bridge.requestLimitExceeded)
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun refetchChecksPermissionAndResponseSizeAndDoesNotUseAnOldCachedResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val authority = ExecutionAuthority()
            val id = authority.issue("a", "legado", "1")
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("default", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("current", server.url("/").toString(), cache = CacheMode.ReadThrough)
                server.enqueue(MockResponse().setBody("old"))
                assertTrue(session.execute(request) is BrokerResult.Success)
                server.enqueue(MockResponse().setBody("new").setResponseCode(202).setHeader("X-New", "yes"))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), currentRequest = request).use { bridge ->
                    assertEquals(ExecutionResult.Success("[\"new\",202,\"yes\"]"), script(id, bridge,
                        "var r=java.getStrResponse(null,null);[r.getBody(),r.code(),r.header('X-New')]"))
                }
                assertEquals(2, server.requestCount)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), currentRequest = request.copy(url = "https://denied.invalid/")).use { bridge ->
                    assertTrue(runCatching { bridge.call("java.getStrResponse", args) }.isFailure)
                    assertEquals(hnovel.network.FailureCode.OriginDenied, bridge.requestFailure!!.code)
                }
                server.enqueue(MockResponse().setBody("x".repeat(1024)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxDataBytes = 128), currentRequest = request).use { bridge ->
                    assertTrue(runCatching { bridge.call("java.getStrResponse", args) }.isFailure)
                    assertEquals(hnovel.network.FailureCode.ResponseTooLarge, bridge.requestFailure!!.code)
                }
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun explicitPostIsNotReplayedOnFailureTimeoutOrCancellation() = runBlocking {
        for (mode in listOf("failure", "timeout", "cancel")) MockWebServer().use { server ->
            server.start()
            val authority = ExecutionAuthority()
            val id = authority.issue(mode, "legado", "1")
            SourceBroker(directory.root.toPath().resolve(mode)).use { broker ->
                val session = broker.open(SourceScope("default", mode, "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                server.enqueue(MockResponse().setSocketPolicy(if (mode == "failure") SocketPolicy.DISCONNECT_AFTER_REQUEST else SocketPolicy.NO_RESPONSE))
                val request = BrokerRequest("post", server.url("/").toString(), method = "POST", body = "synthetic body", retry = 3)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(timeoutMillis = if (mode == "cancel") 10000 else 1000), currentRequest = request).use { bridge ->
                    val pending = async(Dispatchers.IO) { runCatching { bridge.call("java.getStrResponse", args) } }
                    val sent = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }!!
                    assertEquals("POST", sent.method)
                    assertEquals("synthetic body", sent.body.readUtf8())
                    if (mode == "cancel") { withTimeout(3000) { pending.cancelAndJoin() }; assertTrue(pending.isCancelled) }
                    else {
                        assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                        assertEquals(if (mode == "timeout") hnovel.network.FailureCode.Timeout else hnovel.network.FailureCode.Network, bridge.requestFailure!!.code)
                    }
                    assertEquals(1, server.requestCount)
                    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                }
            }
        }
    }

    private fun script(id: ExecutionIdentity, bridge: SourceExecutionBroker, code: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(code), bridge.limits)
        val output = WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args -> runBlocking { bridge.call(name, args) } })
        return ExecutionWire.decodeResult(output.toByteArray())
    }
}
