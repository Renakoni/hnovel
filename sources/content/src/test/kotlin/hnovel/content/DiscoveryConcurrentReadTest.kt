package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryConcurrentReadTest {
    private fun definition(raw: JsonObject, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("Books::/list"),
        "ruleExplore" to buildJsonObject {
            put("bookList", "@js:java.getElements('li')"); put("name", "h2@text"); put("bookUrl", "a@href")
        }
    ) + extra)
    private fun html(path: String) = (1..30).joinToString("") { "<li><h2>$path $it</h2><a href='/book/$it'>Read</a></li>" }

    @Test fun twoIndependentHttpReadsReallyOverlapAndKeepAllPreviewBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    entered.countDown()
                    try { check(release.await(5, TimeUnit.SECONDS)) }
                    finally { active.decrementAndGet() }
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { definition(it) }.use { source ->
                val pages = requireNotNull(source.openDiscovery("parallel").concurrentPreviews(listOf("/one", "/two"), emptyMap()))
                val loads = pages.map { async { it.page(1) } }
                try {
                    withContext(Dispatchers.IO) { assertTrue("Both requests must arrive before either response", entered.await(5, TimeUnit.SECONDS)) }
                } finally { release.countDown() }
                assertEquals(listOf("/one 1", "/two 1"), loads.awaitAll().map { it.books.first().title })
                assertTrue(loads.awaitAll().all { it.books.size == 6 })
                assertEquals(2, maximum.get())
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun prepareFailureBelongsToItsPageAndDoesNotPreventAnotherRead() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(html("working"))
            }
            fixture.source { definition(it) }.use { source ->
                val pages = requireNotNull(source.openDiscovery("mixed").concurrentPreviews(
                    listOf("/bad,{\"method\":\"INVALID\"}", "/working"), emptyMap()))
                val failed = runCatching { pages.first().page(1) }.exceptionOrNull()
                assertTrue(failed.toString(), failed is SourceContentException)
                assertEquals(6, pages.last().page(1).books.size)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun generatedPostsAndSharedRuleStateFallBackBeforeAnyRequest() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { definition(it) }.use { source ->
                val session = source.openDiscovery("post")
                assertNull(session.concurrentPreviews(listOf("/one", "@js:'/two,'+JSON.stringify({method:'POST',body:'x=1'})"), emptyMap()))
                assertEquals(0, fixture.server.requestCount)
            }
            for (extra in listOf(
                mapOf("loginCheckJs" to JsonPrimitive("result")),
                mapOf("jsLib" to JsonPrimitive("var helper=1")),
                mapOf("ruleExplore" to buildJsonObject { put("bookList", "@js:java.put('x','y');java.getElements('li')"); put("name", "h2@text"); put("bookUrl", "a@href") }),
                mapOf("ruleBookInfo" to buildJsonObject { put("name", "@js:java.get('x')") })
            )) fixture.source { definition(it, extra) }.use { source ->
                assertNull(source.openDiscovery("stateful").concurrentPreviews(listOf("/one", "/two"), emptyMap()))
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun refreshWhileReadingCannotRepopulateThePreviewCache() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/one" && entered.count > 0) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    }
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { definition(it) }.use { source ->
                val page = requireNotNull(source.openDiscovery("parallel").concurrentPreviews(listOf("/one", "/two"), emptyMap())).first()
                val pending = async { page.page(1) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    withTimeout(3000) { source.openDiscovery("refresh").catalog(refresh = true, homepage = true) }
                } finally { release.countDown() }
                assertEquals(6, pending.await().books.size)
                assertEquals(30, source.openDiscovery("full").openPages("/one", emptyMap()).page(1).books.size)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun routeRetirementAndCancellationCannotPublishAnOldPage() = runBlocking {
        for (cancel in listOf(false, true)) {
            val route = SourceNetworkRoute.systemDefault()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            RuleSourceFixture(route = SourceRouteProvider { route }, browser = BrowserExecutor { _, request, _, guard, _ ->
                entered.complete(Unit); release.await()
                guard.commit {}
                BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), html("old").toByteArray(), "UTF-8", 0))
            }).use { fixture -> fixture.source { definition(it, mapOf("browserRead" to JsonPrimitive(true))) }.use { source ->
                val page = requireNotNull(source.openDiscovery("parallel").concurrentPreviews(listOf("/one", "/two"), emptyMap())).first()
                val pending = async { runCatching { page.page(1) } }
                withTimeout(5000) { entered.await() }
                if (cancel) pending.cancel() else { route.invalidate(); source.close() }
                release.complete(Unit)
                pending.join()
                if (cancel) assertTrue(pending.isCancelled) else assertTrue(pending.await().isFailure)
            } }
        }
    }
}
