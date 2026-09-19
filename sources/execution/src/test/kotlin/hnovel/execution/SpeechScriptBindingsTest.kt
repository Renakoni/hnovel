package hnovel.execution

import hnovel.network.BrokerLimits
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import hnovel.network.NetworkGrant
import hnovel.network.RequestCompiler
import hnovel.network.CompiledRequest
import hnovel.rhino.HostBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

class SpeechScriptBindingsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun generatedSpeechRequestDoesNotEvaluateTemplatesFromTheBookText() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("speech", "http-tts", "1")
        val text = "中文 before {{40+2}} after"
        val rule = "@js:'https://example.org/,'+JSON.stringify({method:'POST',body:{text:speakText}})"
        val task = ExecutionTask.Script("host.call('request.speech',result)", result = JsonPrimitive(rule), speakText = text)
        val response = IsolatedExecutor(authority = authority).execute(identity, task, ExecutionLimits(timeoutMillis = 15000)) as ExecutionResult.Success
        val prepared = Json.parseToJsonElement(response.output).jsonObject
        val request = (RequestCompiler().compile("speech", prepared.getValue("rule").jsonPrimitive.content, "",
            speakText = text, expandTemplates = prepared.getValue("templates").jsonPrimitive.boolean) as CompiledRequest.Ready).request
        assertEquals(text, Json.parseToJsonElement(request.body!!).jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun nestedSpeechRequestPreservesLiteralTemplatesThroughTheWorkerAndNetworkCompiler() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("speech", "http-tts", "1")
        val text = "中文 before {{40+2}} {{speakSpeed}} @js:literal <js>literal</js> after"
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("ok"))
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { owner ->
                val session = owner.open(SourceScope("default", "speech", "http-tts"), listOf(NetworkGrant(base, true)))
                val limits = ExecutionLimits(timeoutMillis = 15000)
                SourceExecutionBroker(identity, authority, session, limits, baseUrl = base, speakText = text).use { broker ->
                    val task = ExecutionTask.Script("java.ajax(baseUrl+','+JSON.stringify({method:'POST',body:{text:speakText}}))", baseUrl = base, speakText = text)
                    val wire = ExecutionWire.encode(identity, task, limits)
                    val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                    assertEquals(ExecutionResult.Success("\"ok\""), result)
                    val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals(text, Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("text").jsonPrimitive.content)
                }
            }
        }
    }

    @Test fun isolatedWorkerReceivesTextAsDataAndCanPrepareSpeechExpressionsAndHeaders() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("speech", "http-tts", "1")
        val text = "中文 '); throw new Error('injected'); // \" & {{literal}}"
        val url = "https://example.org/?text={{java.encodeURI(speakText)}}&speed={{(speakSpeed+5)/10}}"
        val task = ExecutionTask.Script(
            "({text:speakText,speed:speakSpeed,request:host.call('request.speech',result),headers:host.call('request.headers'),raw:source.getHeader()})",
            result = JsonPrimitive(url), sourceHeaderRule = "@js:({ 'X-Voice': 'voice-' + speakSpeed })",
            speakText = text, speakSpeed = 10,
        )
        val response = IsolatedExecutor(authority = authority).execute(identity, task, ExecutionLimits(timeoutMillis = 15000)) as ExecutionResult.Success
        val result = Json.parseToJsonElement(response.output).jsonObject
        assertEquals(text, result.getValue("text").jsonPrimitive.content)
        assertEquals(10, result.getValue("speed").jsonPrimitive.int)
        assertEquals("voice-10", result.getValue("headers").jsonObject.getValue("X-Voice").jsonPrimitive.content)
        assertEquals(task.sourceHeaderRule, result.getValue("raw").jsonPrimitive.content)
        val prepared = result.getValue("request").jsonObject
        val compiled = RequestCompiler().compile("speech", prepared.getValue("rule").jsonPrimitive.content, "", speakText = text,
            templateValues = prepared.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }) as CompiledRequest.Ready
        assertTrue(compiled.request.url.endsWith("&speed=1.5"))
        assertFalse(compiled.request.url.contains("{{literal}}"))
    }

    @Test fun brokerRejectsMismatchedSpeechDataAndOrdinaryInvocationsKeepTheirBindings() {
        val authority = ExecutionAuthority()
        val identity = authority.issue("speech", "http-tts", "1")
        SourceBroker(directory.root.toPath(), limits = BrokerLimits()).use { owner ->
            val session = owner.open(SourceScope("default", "speech", "http-tts"), emptyList())
            SourceExecutionBroker(identity, authority, session, ExecutionLimits(), speakText = "one", speakSpeed = 10).use { broker ->
                assertTrue(broker.matchesTaskContext(ExecutionTask.Script("1", speakText = "one")))
                assertFalse(broker.matchesTaskContext(ExecutionTask.Script("1", speakText = "two")))
                assertFalse(broker.matchesTaskContext(ExecutionTask.Script("1", speakText = "one", speakSpeed = 20)))
            }
        }
        val ordinary = authority.issue("ordinary", "legado", "1")
        assertEquals(ExecutionResult.Success("\"undefined\""), IsolatedExecutor(authority = authority).execute(ordinary,
            ExecutionTask.Script("typeof speakText"), ExecutionLimits(timeoutMillis = 15000)))
    }
}
