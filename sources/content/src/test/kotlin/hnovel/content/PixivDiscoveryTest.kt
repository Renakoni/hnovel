package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PixivDiscoveryTest {
    private val browser = object : BrowserExecutor {
        override suspend fun defaultUserAgent() = "Test WebView-UA"
        override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
            guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("Unexpected browser request")
    }

    private fun definition(fixture: RuleSourceFixture, cachedAccount: Boolean = true): JsonObject {
        // Exercise the shipped source rather than a second, simplified copy of its rules.
        val original = Json.parseToJsonElement(File(
            "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
            .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
        val local = Json.parseToJsonElement(original.toString().replace(
            "https://www.pixiv.net", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
        val setup = """
            if($cachedAccount) cache.put('pixivUid','12345');
            cache.put('pixivCookie','PHPSESSID=12345_test');
            cache.put('pixivCsrfToken','test-token');
            cache.put('checkTimes','1');
            var testSettings=setDefaultSettings();
            testSettings.IPDirect=false;testSettings.FAST=true;testSettings.DEBUG=false;
            putInCacheObject('pixivSettings',testSettings);
        """.trimIndent()
        return JsonObject(local + ("exploreUrl" to JsonPrimitive("@js:" + setup +
            local.getValue("exploreUrl").jsonPrimitive.content.removePrefix("@js:"))))
    }

    @Test fun publicAndPrivateBookmarksExpandTheCachedAccountId() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":false,"body":{"works":[{"id":"98765","title":"Test bookmark",
                        "userId":"11111","userName":"Test author","tags":["test"],"textCount":1000,
                        "description":"Test description","url":"/cover.jpg","seriesId":null,
                        "bookmarkData":{"id":"54321","private":false},
                        "createDate":"2026-01-01T00:00:00+09:00","updateDate":"2026-01-01T00:00:00+09:00"}]}}""")
            }
            fixture.source { definition(fixture) }.use { source ->
                val discovery = source.openDiscovery("bookmarks")
                val bookmarks = discovery.catalog().rows.filter { it.url.contains("/novels/bookmarks?") }
                assertEquals(2, bookmarks.size)
                for ((index, row) in bookmarks.withIndex()) {
                    val page = try { discovery.preview(row.url, emptyMap()) }
                    catch (failure: SourceContentException) {
                        throw AssertionError("${failure.message}: ${failure.denial}; ${failure.diagnostic}", failure)
                    }
                    assertEquals(1, page.books.size)
                    assertTrue(page.books.single().title.contains("Test bookmark"))
                    // The original loginCheckJs explicitly refetches through java.getStrResponse.
                    repeat(2) {
                        val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals("/ajax/user/12345/novels/bookmarks", request.requestUrl!!.encodedPath)
                        assertEquals(if (index == 0) "show" else "hide", request.requestUrl!!.queryParameter("rest"))
                        assertEquals("0", request.requestUrl!!.queryParameter("offset"))
                    }
                }
            }
        }
    }

    @Test fun privateBookmarksCompleteWhileTheOriginalSourcesPublicBookmarksAreBlocked() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.queryParameter("rest") == "show") {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"error":false,"body":{"works":[]}}""")
                }
            }
            fixture.source { definition(fixture) }.use { source ->
                val discovery = source.openDiscovery("parallel")
                val urls = discovery.catalog().rows.filter { it.url.contains("/novels/bookmarks?") }.map { it.url }
                assertEquals(2, urls.size)
                val pages = requireNotNull(discovery.concurrentPreviews(urls, emptyMap()))
                val publicPage = async { pages.first().page(1) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    assertTrue(withTimeout(5000) { pages.last().page(1) }.books.isEmpty())
                    assertFalse(publicPage.isCompleted)
                    assertEquals(3, fixture.server.requestCount)
                } finally { release.countDown() }
                assertTrue(publicPage.await().books.isEmpty())
                assertEquals(4, fixture.server.requestCount)
            }
        }
    }

    @Test fun coldAccountIsInitializedBeforeBookmarkUrlsAreSnapshotted() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":false,"body":{"page":{"recommend":{"ids":[]}},"thumbnails":{"novel":[]}}}""")
            }
            fixture.source { definition(fixture, cachedAccount = false) }.use { source ->
                val discovery = source.openDiscovery("cold")
                val catalog = discovery.catalog()
                val bookmarks = catalog.rows.filter { it.url.contains("/novels/bookmarks?") }.map { it.url }
                assertNull(discovery.concurrentPreviews(bookmarks, emptyMap()))
                assertEquals(0, fixture.server.requestCount)
                val recommendation = catalog.rows.first { it.url.contains("/ajax/top/novel?") }
                discovery.preview(recommendation.url, emptyMap())
                assertEquals(2, fixture.server.requestCount)
                assertNotNull(discovery.concurrentPreviews(bookmarks, emptyMap()))
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }
}
