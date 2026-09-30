package hnovel.network

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class BrowserIdentityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun automaticIdentityIsResolvedOnceAndSharedByConcurrentHttpAndBrowserRequests() = runBlocking {
        val calls = AtomicInteger()
        val seen = mutableListOf<String?>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String { delay(20); return "actual-provider-${calls.incrementAndGet()}" }
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                seen += request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                return BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        MockWebServer().use { server ->
            server.start(); repeat(4) { server.enqueue(MockResponse()) }
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(SourceScope("test", "identity", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                coroutineScope { (1..4).map { async { session.execute(BrokerRequest("http-$it", base)) } }.awaitAll() }
                repeat(4) { assertEquals("actual-provider-1", server.takeRequest().getHeader("User-Agent")) }
                repeat(2) { session.execute(BrokerRequest("browser-$it", base, browser = BrowserOptions())) }
                session.execute(BrokerRequest("explicit", base, headers = mapOf("User-Agent" to "compatible-source"), browser = BrowserOptions()))
                assertEquals(listOf("actual-provider-1", "actual-provider-1", "compatible-source"), seen)
                assertEquals("actual-provider-1", session.requestUserAgent(base))
                assertEquals("actual-provider-1", session.webViewUserAgent())
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun headlessHostsRetainTheirConfiguredCompatibilityDefault() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse())
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("test", "headless", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                assertTrue(session.execute(BrokerRequest("http", base)) is BrokerResult.Success)
                assertEquals(DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            }
        }
    }

    @Test fun identityReadBeforeTheFirstRequestResolvesTheProviderOnce() = runBlocking {
        val calls = AtomicInteger()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String = "provider-${calls.incrementAndGet()}"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("No navigation expected")
        }
        val base = "https://fixture.invalid/"
        SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
            val session = broker.open(SourceScope("test", "before-request", "legado"), listOf(NetworkGrant(base)))
            session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
            assertEquals("provider-1", session.requestUserAgent(base))
            assertEquals("compatible", session.requestUserAgent(base, mapOf("User-Agent" to "compatible")))
            assertEquals("provider-1", session.webViewUserAgent())
            assertEquals(1, calls.get())
        }
    }

    @Test fun explicitRequestIdentityDoesNotRequireTheDefaultBrowserProvider() = runBlocking {
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String = error("Explicit identity must not initialize the default provider")
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("No browser navigation expected")
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse())
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(SourceScope("test", "explicit", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                val headers = mapOf("uSeR-aGeNt" to "source-compatible")
                assertEquals("source-compatible", session.requestUserAgent(base, headers))
                assertTrue(session.execute(BrokerRequest("http", base, headers = headers)) is BrokerResult.Success)
                assertEquals("source-compatible", server.takeRequest().getHeader("User-Agent"))
            }
        }
    }
}
