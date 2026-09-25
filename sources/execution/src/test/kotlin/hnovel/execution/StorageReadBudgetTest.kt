package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageReadBudgetTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun repeatedReadsLeaveBudgetForNetworkAndStillObserveStorageChanges() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                session.write(StorageRequest(StorageArea.Cache, "value:setting", "first", 0))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 2), base).use { bridge ->
                    val key = listOf(JsonPrimitive("setting"))
                    repeat(128) { assertEquals(JsonPrimitive("first"), bridge.call("cache.get", key)) }
                    session.write(StorageRequest(StorageArea.Cache, "value:setting", "second", 0))
                    assertEquals(JsonPrimitive("second"), bridge.call("cache.get", key))
                    session.write(StorageRequest(StorageArea.Cache, "value:setting", null))
                    assertEquals(JsonNull, bridge.call("cache.get", key))
                    server.enqueue(MockResponse().setBody("chapter"))
                    assertEquals(JsonPrimitive("chapter"), bridge.call("java.ajax", listOf(JsonPrimitive("/chapter"))))
                    assertTrue(runCatching { bridge.call("java.ajax", listOf(JsonPrimitive("/second"))) }.isFailure)
                    assertTrue(bridge.requestLimitExceeded)
                    assertEquals(1, server.requestCount)
                    authority.revoke(id)
                    assertTrue(runCatching { bridge.call("cache.get", key) }.isFailure)
                }
            }
        }
    }

    @Test fun uniqueReadsStayBoundedAndFailedReservationCannotBeReused() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        SourceBroker(directory.root.toPath()).use { sessions ->
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1)).use { bridge ->
                assertEquals(JsonNull, bridge.call("cache.get", listOf(JsonPrimitive("a"))))
                repeat(2) {
                    assertTrue(runCatching { bridge.call("cache.get", listOf(JsonPrimitive("b"))) }.isFailure)
                    assertTrue(bridge.requestLimitExceeded)
                }
                assertTrue(runCatching { bridge.call("source.get", listOf(JsonPrimitive("a"))) }.isFailure)
                assertTrue(runCatching { bridge.call("cache.getFile", listOf(JsonPrimitive("a"))) }.isFailure)
            }
        }
    }

    @Test fun everyWriteStillConsumesBudgetAndReadsSeeTheLatestWrite() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("a", "legado", "1", "fixture")
        SourceBroker(directory.root.toPath()).use { sessions ->
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 3)).use { bridge ->
                val key = listOf(JsonPrimitive("setting"))
                assertEquals(JsonPrimitive(""), bridge.call("source.get", key))
                for (value in listOf("one", "two")) {
                    bridge.call("source.put", key + JsonPrimitive(value))
                    assertEquals(JsonPrimitive(value), bridge.call("source.get", key))
                }
                assertTrue(runCatching { bridge.call("source.put", key + JsonPrimitive("three")) }.isFailure)
                assertEquals(StorageResult.Value("two"), session.read(StorageRequest(StorageArea.Config, "value:setting")))
            }
        }
    }
}
