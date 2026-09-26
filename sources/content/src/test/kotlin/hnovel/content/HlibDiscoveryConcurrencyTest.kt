package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class HlibDiscoveryConcurrencyTest {
    @Test fun shippedHlibStartsEveryPreviewBeforeTheFirstPageFinishes() = runBlocking {
        val entered = AtomicInteger()
        val allEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Test WebView-UA"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                if (entered.incrementAndGet() == 4) allEntered.complete(Unit)
                allEntered.await()
                if ("type=day" in request.url) releaseFirst.await()
                val body = (1..6).joinToString("") { index ->
                    "<li class='list-group-item'><a href='/n/$index'>Book $index</a></li>"
                }.let { "<html><body><div class='container'>$it</div></body></html>" }
                return BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(),
                    body.toByteArray(), "UTF-8", 0))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            val original = Json.parseToJsonElement(File(
                "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
                .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://hlib.cc" }
            val local = Json.parseToJsonElement(original.toString().replace(
                "https://hlib.cc", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            fixture.source { local }.use { source ->
                val discovery = source.openDiscovery("hlib-parallel")
                val catalog = discovery.catalog(homepage = true)
                val urls = requireNotNull(catalog.homepage).map { it.url }
                assertEquals(4, urls.size)
                val pages = requireNotNull(discovery.concurrentPreviews(urls, catalog.values))
                val loads = pages.map { async { it.page(1) } }
                try {
                    withTimeout(10000) {
                        allEntered.await()
                        assertTrue(loads.drop(1).awaitAll().all { it.books.size == 6 })
                    }
                    assertFalse("The blocked daily ranking must still be loading", loads.first().isCompleted)
                    assertEquals(4, entered.get())
                } finally { releaseFirst.complete(Unit) }
                assertEquals(6, loads.first().await().books.size)
            }
        }
    }
}
