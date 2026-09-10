package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class NetworkBridgeTest {
    @get:Rule val folder = TemporaryFolder()
    private val authority = ExecutionAuthority()
    private val id = authority.issue("a", "legado", "1", "fixture")
    private fun script(broker: SourceExecutionBroker, code: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(code), broker.limits)
        return ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args ->
            runBlocking { broker.call(name, args) }
        }).toByteArray())
    }

    @Test fun connectFollowsRedirectsButGetHeadPostExposeTheOriginalResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
                    server.enqueue(MockResponse().setBody("chapter").addHeader("X-Test", "one").addHeader("X-Test", "two"))
                    assertEquals(ExecutionResult.Success("[200,\"chapter\",\"two\"]"), script(broker,
                        "var r=java.connect('/start','{\"X-Request\":\"yes\"}');[r.code(),r.body(),r.headers().get('x-test')]"))
                    assertEquals("yes", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Request"))
                    assertEquals("/final", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    for (method in listOf("get", "head", "post")) {
                        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://not-granted.invalid/"))
                        val extra = if (method == "post") "'raw=value'," else ""
                        assertEquals(ExecutionResult.Success("[302,\"https://not-granted.invalid/\"]"), script(broker,
                            "var r=java.$method('$base',${extra}{});[r.statusCode(),r.header('Location')]"))
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        assertEquals(method.uppercase(), request.method)
                        if (method == "post") assertEquals("raw=value", request.body.readUtf8())
                    }
                    assertEquals(5, server.requestCount)
                }
            }
        }
    }

    @Test fun parallelBatchPreservesInputOrderAndReservesItsWholeBudget() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path!!)
                    .setBodyDelay(if (request.path == "/first") 150 else 0, TimeUnit.MILLISECONDS)
            }
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1), base).use { broker ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), script(broker, "java.ajaxAll(['/first','/second'])"))
                }
                assertEquals(0, server.requestCount)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 2), base).use { broker ->
                    assertEquals(ExecutionResult.Success("[\"/first\",\"/second\"]"), script(broker,
                        "java.ajaxAll(['/first','/second']).map(function(r){return r.body()})"))
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun cacheHitsRespectPerRequestByteLimitsAndRedirectMode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("cache", server.url("/small").toString(), cache = CacheMode.ReadThrough)
                server.enqueue(MockResponse().setBody("123456"))
                assertTrue(session.execute(request) is BrokerResult.Success)
                assertEquals(BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.ResponseTooLarge),
                    session.execute(request.copy(cache = CacheMode.Only, maxResponseBytes = 3)))
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
                server.enqueue(MockResponse().setBody("final"))
                val redirect = request.copy(url = server.url("/redirect").toString())
                assertTrue(session.execute(redirect) is BrokerResult.Success)
                assertEquals(BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.CacheMiss),
                    session.execute(redirect.copy(cache = CacheMode.Only, followRedirects = false)))
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun batchCancellationReleasesNetworkPermitsAndOversizedResponsesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath(), limits = BrokerLimits(concurrency = 1)).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                val broker = SourceExecutionBroker(id, authority, session, ExecutionLimits(), base)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { runCatching { broker.call("java.ajaxAll", listOf(JsonArray(listOf(JsonPrimitive("/one"), JsonPrimitive("/two"))))) } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                broker.close()
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                server.enqueue(MockResponse().setBody("next"))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { next ->
                    assertEquals(JsonPrimitive("next"), withTimeout(3000) { next.call("java.ajax", listOf(JsonPrimitive("/next"))) })
                    server.enqueue(MockResponse().setBody("x".repeat(BridgeWire.MAX_BYTES + 1)))
                    assertTrue(runCatching { next.call("java.connect", listOf(JsonPrimitive("/large"))) }.isFailure)
                }
            }
        }
    }
}
