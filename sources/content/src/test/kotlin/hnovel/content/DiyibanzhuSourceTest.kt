package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DiyibanzhuSourceTest {
    private val original = Json.parseToJsonElement(File(
        "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
        .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "http://m.diyibanzhu.buzz" }

    private fun definition(fixture: RuleSourceFixture) = Json.parseToJsonElement(original.toString()
        .replace("http://m.diyibanzhu.buzz", fixture.server.url("/").toString().removeSuffix("/"))
        .replace("https://www.diyibanzi.com", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject

    // Structural fixtures from the redirect destination, without site content or scripts.
    private val listing = """
        <ul class="flex"><li>
          <div class="img_span"><a href="/book/1/"><img src="/placeholder.jpg" data-original="/cover.jpg"></a></div>
          <div class="w100"><a href="/book/1/"><h2>Fixture book</h2></a>
            <p class="indent">Fixture introduction</p>
            <div class="li_bottom"><a href="/author/fixture/"><i class="fa fa-user-circle-o">Fixture author</i></a></div>
          </div>
        </li></ul>
    """.trimIndent()
    private val search = """
        <ul class="flex"><li class="searchresult">
          <div class="img_span"><a href="/book/1/"><img src="/placeholder.jpg" data-original="/cover.jpg"></a></div>
          <div><a href="/book/1/"><h3>Fixture <span class="hot">book</span></h3></a>
            <p><i class="fa fa-user-circle-o">&nbsp;</i>Fixture author&nbsp;&nbsp;<span class="s_gray">Updated</span></p>
            <p class="searchresult_p">Fixture introduction</p>
            <p><a href="/read/1/12.html">Second chapter</a></p>
          </div>
        </li></ul>
    """.trimIndent()

    @Test fun shippedRulesReadMigratedDiscoveryAndTheCompleteReadingChain() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val html = when (request.path) {
                        "/sort/1/", "/quanben/sort/1/" -> listing
                        "/book/1/" -> """
                            <meta property="og:novel:book_name" content="Fixture book">
                            <meta property="og:novel:author" content="Fixture author">
                            <meta property="og:description" content="Fixture introduction">
                            <meta property="og:novel:category" content="Fantasy">
                            <meta property="og:novel:status" content="Complete">
                            <meta property="og:novel:read_url" content="/book/1/">
                            <meta property="og:novel:lastest_chapter_name" content="Second chapter">
                            <div class="novel_info_main"><img src="/cover.jpg"></div>
                            <ul class="chapter_list"><li><a href="/read/1/12.html">Second chapter</a></li></ul>
                            <ul id="ul_all_chapters"><li><a href="/read/1/11.html">First chapter</a></li>
                              <li><a href="/read/1/12.html">Second chapter</a></li></ul>
                        """.trimIndent()
                        "/read/1/11.html" -> "<article id='article'><p>First page.</p></article>" +
                            "<a id='next_url' href='/read/1/11_2.html'>下一页</a>"
                        "/read/1/11_2.html" -> "<article id='article'><p>Last page.</p></article>" +
                            "<a id='next_url' href='/read/1/12.html'>下一章</a>"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html)
                }
            }
            fixture.source { definition(fixture) }.use { source ->
                val books = source.discovery(fixture.server.url("/sort/1/").toString())
                assertEquals(1, books.size)
                val book = books.single()
                assertEquals("Fixture book", book.title)
                // Discovery defers metadata; information() resolves the stored preview first.
                val discovery = source.openDiscovery("migrated")
                val catalog = discovery.catalog()
                assertEquals(10, catalog.rows.size)
                assertEquals(listOf(book.id), discovery.preview(catalog.rows.first().url, catalog.values).books.map { it.id })
                val info = source.information(book.id)
                assertEquals("Fixture book", info.title)
                assertEquals("Fixture author", info.author)
                assertEquals(fixture.server.url("/cover.jpg").toString(), info.coverUrl)
                val chapters = source.directory(book.id)
                assertEquals(listOf("First chapter", "Second chapter"), chapters.map { it.title })
                val content = source.content(book.id, chapters.first().id)
                val text = content.parts.mapNotNull { it.text }.joinToString("\n")
                assertTrue(text.contains("First page."))
                assertTrue(text.contains("Last page."))
            }
        }
    }

    @Test fun shippedSearchUsesUtf8PostAndReadsTheBookRatherThanTheLatestChapter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8").setBody(search)
            }
            fixture.source { definition(fixture) }.use { source ->
                val book = source.search("太子").single()
                assertEquals("Fixture book", book.title)
                assertEquals("Fixture author", book.author)
                assertEquals(fixture.server.url("/book/1/").toString(), book.id)
                assertEquals(fixture.server.url("/cover.jpg").toString(), book.coverUrl)
                val request = checkNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/search/", request.path)
                assertEquals("POST", request.method)
                assertEquals("searchkey=%E5%A4%AA%E5%AD%90&searchtype=all", request.body.readUtf8())
            }
        }
    }
}
