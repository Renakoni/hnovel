package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class SourceLibraryTest {
    @get:Rule val directory = TemporaryFolder()
    private fun definition(vararg urls: String) = buildJsonObject { urls.forEachIndexed { i, url -> put("part$i", url) } }.toString()

    @Test fun orderedDownloadsAreCachedAsDataAndEvaluatedAsSeparateWorkerSegments() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val definition = definition(server.url("/one").toString(), "ignored inline text", server.url("/two").toString())
                server.enqueue(MockResponse().setBody("'use strict'; var order=['one'];"))
                server.enqueue(MockResponse().setBody("order.push('two'); function loose(){return this!==undefined;}"))
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { broker ->
                    val scripts = broker.loadLibrary(definition)
                    assertEquals(scripts, broker.loadLibrary(definition))
                    assertEquals(2, server.requestCount)
                    assertEquals("/one", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    assertEquals("/two", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    val wire = ExecutionWire.encode(id, ExecutionTask.Script("[order,loose()]", libraryCode = definition), ExecutionLimits(), scripts)
                    val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray())
                    assertEquals(ExecutionResult.Success("[[\"one\",\"two\"],true]"), result)
                }
            }
        }
    }

    @Test fun persistentCacheCannotBypassChangedGrants() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        MockWebServer().use { server ->
            server.start()
            val definition = definition(server.url("/lib").toString())
            SourceBroker(directory.root.toPath()).use { sessions ->
                val scope = SourceScope("fixture", "a", "legado")
                val session = sessions.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                server.enqueue(MockResponse().setBody("var data='cached';"))
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { assertEquals(1, it.loadLibrary(definition).size) }
                session.close()
                val denied = sessions.open(scope, emptyList())
                assertFalse(authority.accepts(id))
                val next = authority.issue("a", "legado", "1", "fixture")
                SourceExecutionBroker(next, authority, denied, ExecutionLimits()).use {
                    assertTrue(runCatching { it.loadLibrary(definition) }.isFailure)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun sourcesAndAccountsDoNotShareAuthenticatedLibraryCache() = runBlocking {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                for ((source, account) in listOf("a" to 0L, "b" to 0L, "a" to 1L)) {
                    val id = authority.issue(source, "legado", "1", "fixture", account)
                    val session = sessions.open(SourceScope("fixture", source, "legado", account), listOf(NetworkGrant(server.url("/").toString(), true)))
                    server.enqueue(MockResponse().setBody("var owner='$source:$account';"))
                    SourceExecutionBroker(id, authority, session, ExecutionLimits()).use {
                        assertEquals(listOf("var owner='$source:$account';"), it.loadLibrary(definition(server.url("/lib").toString())))
                    }
                }
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun revokingDownloadPreventsLibraryAndCookieCommits() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val definition = definition(server.url("/lib").toString())
                server.enqueue(MockResponse().setBody("late").addHeader("Set-Cookie", "token=late; Path=/").setBodyDelay(400, TimeUnit.MILLISECONDS))
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { broker ->
                    val pending = async { runCatching { broker.loadLibrary(definition) } }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                    authority.revoke(id)
                    assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                }
                val next = authority.issue("a", "legado", "1", "fixture")
                server.enqueue(MockResponse().setBody("fresh"))
                SourceExecutionBroker(next, authority, session, ExecutionLimits()).use { assertEquals(listOf("fresh"), it.loadLibrary(definition)) }
                assertNull(server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            }
        }
    }

    @Test fun downloadAndAggregateBudgetsRejectLargeLibrariesAndExhaustedTickets() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                server.enqueue(MockResponse().setBody("x".repeat(SourceLibraryDefinition.MAX_CHARS)))
                val definition = definition(server.url("/lib").toString())
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use {
                    assertTrue(runCatching { it.loadLibrary(definition) }.exceptionOrNull() is LibraryTooLarge)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 0)).use {
                    assertTrue(runCatching { it.loadLibrary(definition) }.isFailure)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun malformedAndDeepDefinitionsFailBeforeRecursiveParsingOrWorkerExecution() {
        assertThrows(IllegalArgumentException::class.java) { SourceLibraryDefinition.urls("{\"lib\":true}") }
        assertThrows(IllegalArgumentException::class.java) { SourceLibraryDefinition.urls("{\"lib\":" + "[".repeat(10000) + "]".repeat(10000) + "}") }
        val id = ExecutionAuthority().issue("a", "legado", "1")
        val wire = ExecutionWire.encode(id, ExecutionTask.Script("1", libraryCode = "{\"lib\":\"https://fixture.invalid/lib\"}"), ExecutionLimits())
        assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied),
            ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray()))
    }

    @Test fun braceDelimitedBlocksFollowThePinnedMapDispatchWithoutScriptFallback() {
        // SharedJsScope uses String.isJsonObject (trimmed braces) before GSON parsing. A
        // failed map is not reinterpreted as executable code in the fixed profile.
        val block = "{ var helper = function(){return 42;}; }"
        assertTrue(SourceLibraryDefinition.isUrlMap(block))
        assertTrue(runCatching { SourceLibraryDefinition.urls(block) }.isFailure)
        val id = ExecutionAuthority().issue("a", "legado", "1")
        val rejected = ExecutionWire.encode(id, ExecutionTask.Script("helper()", libraryCode = block), ExecutionLimits())
        assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied),
            ExecutionWire.decodeResult(WorkerMain.executeSerialized(rejected.toString(Charsets.UTF_8)).toByteArray()))
        val inline = ExecutionWire.encode(id, ExecutionTask.Script("helper()", libraryCode = ";$block"), ExecutionLimits())
        assertEquals(ExecutionResult.Success("42"),
            ExecutionWire.decodeResult(WorkerMain.executeSerialized(inline.toString(Charsets.UTF_8)).toByteArray()))
    }
}
