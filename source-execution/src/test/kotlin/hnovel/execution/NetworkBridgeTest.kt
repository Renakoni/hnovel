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
            val reply = runBlocking { broker.call(name, args) }.toString().toByteArray(Charsets.UTF_8)
            Json.parseToJsonElement(BridgeWire.validate(reply))
        }).toByteArray())
    }

    @Test fun responsePayloadIsNotDuplicatedAndOrdinaryPagesFitThroughTheWire() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 32), base).use { broker ->
                    for (body in listOf("x".repeat(28 * 1024), "x".repeat(60000), "\u4E2D".repeat(20000), "\"\\".repeat(12000))) {
                        for (call in listOf("java.get('$base',{})", "java.connect('$base')", "java.ajaxAll(['$base'])[0]")) {
                            server.enqueue(MockResponse().setBody(body))
                            assertEquals("$call, ${body.length} chars", ExecutionResult.Success(body.length.toString()),
                                script(broker, "$call.body().length"))
                        }
                    }
                    server.enqueue(MockResponse().setBody("x".repeat(30000)))
                    server.enqueue(MockResponse().setBody("x".repeat(30000)))
                    assertEquals(ExecutionResult.Success("[30000,30000]"), script(broker,
                        "java.ajaxAll(['$base','$base']).map(function(r){return r.body().length})"))
                    for ((operation, args, field) in listOf(
                        Triple("java.get", listOf(JsonPrimitive(base), JsonObject(emptyMap())), "bytes"),
                        Triple("java.connect", listOf(JsonPrimitive(base)), "body"))) {
                        server.enqueue(MockResponse().setBody("x".repeat(60000)))
                        val snapshot = broker.call(operation, args).jsonObject
                        assertTrue(field in snapshot)
                        assertFalse((if (field == "bytes") "body" else "bytes") in snapshot)
                        assertTrue(snapshot.toString().toByteArray().size < BridgeWire.MAX_BYTES)
                    }
                }
            }
        }
    }

    @Test fun responseBodyMetadataAndBatchLimitsRemainBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    server.enqueue(MockResponse().setBody("x".repeat(hnovel.rhino.ScriptLimits.DEFAULT_BRIDGE_CHARS + 1)))
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), script(broker, "java.get('$base',{})"))
                    server.enqueue(MockResponse().setBody("x".repeat(20000)).addHeader("X-Large", "y".repeat(50000)))
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), script(broker, "java.get('$base',{})"))
                    repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(35000))) }
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), script(broker, "java.ajaxAll(['$base','$base'])"))
                    server.enqueue(MockResponse().setBody("next"))
                    assertEquals(ExecutionResult.Success("\"next\""), script(broker, "java.connect('$base').body()"))
                }
            }
        }
    }

    @Test fun bomBytesAndDeclaredCharsetSurviveBrokerWireBeforeWorkerParsing() = runBlocking {
        val html = "<p>caf\u00e9 \u4E2D</p>"
        val encodings = listOf(
            "UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            "UTF-32LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0),
            "UTF-32BE" to byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()))
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    for ((encoding, bom) in encodings) for (declared in listOf(null, encoding)) {
                        val bytes = bom + html.toByteArray(charset(encoding))
                        server.enqueue(MockResponse().setHeader("Content-Type", "text/html" +
                            (declared?.let { "; charset=$it" } ?: "")).setBody(okio.Buffer().write(bytes)))
                        val result = script(broker, """
                            var r=java.get('$base',{}),before=r.charset(),body=r.body();
                            [before,body,r.parse().select('p').text(),r.bodyAsBytes()]
                        """)
                        assertTrue("$encoding / $declared: $result", result is ExecutionResult.Success)
                        val expected = buildJsonArray {
                            add(declared?.let(::JsonPrimitive) ?: JsonNull)
                            add(bytes.toString(charset(declared ?: "UTF-8")))
                            add("caf\u00e9 \u4E2D")
                            add(JsonArray(bytes.map { JsonPrimitive(it.toInt()) }))
                        }
                        assertEquals("$encoding / $declared", expected, Json.parseToJsonElement((result as ExecutionResult.Success).output))
                    }
                }
            }
        }
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
