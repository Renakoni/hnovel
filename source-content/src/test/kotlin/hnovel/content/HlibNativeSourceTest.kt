package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** Execute the shipped source against neutral documents; never use website accounts. */
class HlibNativeSourceTest {
    private fun raw(base: String) = JsonObject(Json.parseToJsonElement(
        javaClass.getResourceAsStream("/hlib-native.json")!!.bufferedReader().use { it.readText() }
    ).jsonObject + ("bookSourceUrl" to JsonPrimitive(base)))

    @Test fun publicNavbarDoesNotRequestLoginAndEveryReadUsesTheBrowser() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        val browser = BrowserExecutor { _, request, options, _ ->
            assertFalse(options.interactive)
            requests += request
            val path = URI(request.url).path
            val body = if (path == "/tag") """
                <div class='col'><a href='/tag/fiction'>Fiction</a></div>
                <button aria-label='后一页'></button>
            """ else """
                <div id='results' class='container'><ul><li class='list-group-item'>
                <a href='/s/fixture'>Fixture series</a><a href='/u/writer'><span class='text-body'>Writer</span></a>
                </li></ul></div><div id='loading' class='d-none'></div>
            """
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                ("<html><nav><a href='/login'>Login</a></nav>$body</html>").toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(object : BrowserExecutor by browser {
            override suspend fun defaultUserAgent() = "Fixture WebView"
        }).use { fixture -> fixture.source { raw(fixture.server.url("/").toString().trimEnd('/')) }.use { source ->
            val discovery = source.openDiscovery("fixture")
            val home = discovery.catalog(homepage = true)
            assertEquals(listOf("日榜", "周榜", "月榜", "文章"), home.homepage!!.map { it.title })
            assertTrue(requests.isEmpty())
            assertTrue(source.search("fixture").isNotEmpty())
            for ((module, period) in home.homepage.take(3).zip(listOf("day", "week", "month"))) {
                assertTrue(discovery.page(module.url, 1, emptyMap()).isNotEmpty())
                assertEquals("type=$period&p=1", URI(requests.last().url).query)
                assertTrue(discovery.page(module.url, 2, emptyMap()).isEmpty())
            }
            assertTrue(discovery.page(home.homepage.last().url, 1, mapOf("文章排序" to "收藏数")).isNotEmpty())
            assertEquals("sort=like&p=1", URI(requests.last().url).query)
            val tags = discovery.catalog()
            assertEquals(listOf("Fiction"), tags.rows.filter { it.type == "url" }.map { it.title })
            assertFalse(tags.rows.any { it.title == "榜单周期" || it.type == "text" })
            assertEquals(listOf("/n?", "/tag/"), tags.rows.single { it.title == "文章排序" }.targetPrefixes)
            assertEquals("/tag", URI(requests.last().url).path)
            assertTrue(discovery.page(tags.rows.single { it.type == "url" }.url, 1, emptyMap()).isNotEmpty())
            val next = discovery.interact(tags.rows.single { it.title == "下一页标签" }.id)
            assertTrue(next.refresh)
            discovery.catalog(refresh = true)
            assertEquals("sort=pop&p=2", URI(requests.last().url).query)
            assertEquals(0, fixture.server.requestCount)
        } }
    }

    @Test fun tagChallengeKeepsRecoveryAndDoesNotPreventHomepageOrItsLists() = runBlocking {
        val browser = BrowserExecutor { _, request, _, _ ->
            if (URI(request.url).path == "/tag") BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired,
                challenge = BrowserChallengeKind.SiteVerification, verificationRequest = request)
            else BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "<html></html>".toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(object : BrowserExecutor by browser {
            override suspend fun defaultUserAgent() = "Fixture WebView"
        }).use { fixture -> fixture.source { raw(fixture.server.url("/").toString().trimEnd('/')) }.use { source ->
            val discovery = source.openDiscovery("fixture")
            val failure = runCatching { discovery.catalog() }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.BrowserRequired, failure.code)
            assertEquals(BrowserChallengeKind.SiteVerification, failure.verification!!.kind)
            val home = discovery.catalog(homepage = true)
            assertEquals(4, home.homepage!!.size)
            discovery.page(home.homepage.first().url, 1, emptyMap())
            assertEquals(0, fixture.server.requestCount)
        } }
    }
}
