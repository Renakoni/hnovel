package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KnownSourceRulesTest {
    private val definitions = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/known-sources/hoohoo-1.0.3-4.json"))
        .bufferedReader().use { it.readText() }).jsonArray

    @Test fun aliceswRulesExecuteTheMobileChapterAndDesktopCatalogue() = verify(0)
    @Test fun bcshukuRulesPostChapterFieldsWithTheChapterReferer() = verify(1)
    @Test fun fqbookRulesPreserveVolumeHeadersAndFetchTheNonceEndpoint() = verify(2)
    @Test fun jmNovelRulesReadLazyImagesAndDecodeWithoutALocalServer() = verify(3)

    private fun verify(index: Int) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = definitions[index].jsonObject
            val root = original.getValue("bookSourceUrl").jsonPrimitive.content
            val local = fixture.server.url("/").toString()
            val definition = Json.parseToJsonElement(original.toString().replace(root, local).replace(root.removeSuffix("/"), local.removeSuffix("/"))).jsonObject
            // Match the size class of the actual encrypted cover; a tiny byte fixture hides bridge limits.
            val plain = ByteArray(if (index == 3) 37840 else 64) { it.toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte() }
            val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec("f5d965df75336270".toByteArray(), "AES"), IvParameterSpec("97b60394abc2fbe1".toByteArray()))
                doFinal(plain)
            }
            var posted: RecordedRequest? = null
            var nonceRequest: String? = null
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    if (path == "/cover") return MockResponse().setBody(okio.Buffer().write(if (index == 3) encrypted else plain))
                    if (index == 1 && path == "/e/search/index.php") return MockResponse().setResponseCode(302)
                        .setHeader("Location", "$local/e/search/result/index.php?searchid=123".replace("//e/", "/e/"))
                    if (path == "/conapi.php") {
                        posted = request
                        return MockResponse().setBody(buildJsonObject {
                            put("success", "1"); put("content", "<p>First chapter." + "x".repeat(70000) + "</p>")
                        }.toString())
                    }
                    val html = when (index) {
                        0 -> when {
                            path.startsWith("/search") -> "<div class='list-group'><div><h5><a href='/novel/1.html'>Fixture</a></h5></div></div>"
                            path == "/novel/1.html" -> "<div class='detail-box'><div class='top'><p class='xs-title'>Fixture</p></div><div class='imgbox'><img src='/cover'></div></div>"
                            path.startsWith("/other/") -> "<div class='section-list'><a href='/book/1/chapter.html'>One</a></div>"
                            path.startsWith("/book/") -> "<article id='chapterContent'><h3>One</h3><div class='content_txt'><p>First chapter.</p></div></article>"
                            else -> "<a href='/lists/65.html'>Category</a><ul class='rec_rullist'><li><span class='two'><a href='/novel/1.html'>Fixture</a></span></li></ul>"
                        }
                        1 -> when (path) {
                            "/novel1/" -> "<div class='desc'><h3 class='title'>Fixture</h3></div><div class='book'><img src='/cover'></div><div id='list-chapter'><ul class='list-chapter'><li><a href='/novel1/chapter0.html'>One</a></li></ul></div>"
                            "/novel1/chapter0.html" -> """<script>var content={"url":"opaque","mobile":"1","isk":"fixture","novel":"1","chapter":"0"};</script>"""
                            else -> "<a href='/booklist1'>Category</a><div class='one-row'><div class='home-truyendecu'><div class='caption'><h3>Fixture</h3></div><div class='each_truyen'><a href='/novel1/'><img src='/cover'></a></div></div></div>"
                        }
                        2 -> when (path) {
                            "/m/book-1.html" -> "<div class='detail'><div class='right'><h2>Fixture</h2></div><div class='left'><img src='/cover'></div></div>"
                            "/chapterList-1.html" -> "<div class='page_main'><p class='section_title'>Volume</p><ul class='section_list'><li><a href='/read-1.html'>One</a></li></ul></div>"
                            "/read-1.html" -> "<script>var endpoint=\"_getcontent.php?id=1&v=fixture\";</script>"
                            "/_getcontent.php" -> { nonceRequest = request.path; "<p>First chapter.</p>" }
                            else -> "<a href='/m/category.html?catId=1'>Category</a><div class='book-all-list'><div class='bd'><ul><li><div class='right'><a class='name' href='/m/book-1.html'>Fixture</a></div></li></ul></div></div><ul class='book-list'><li><div class='right'><a class='name' href='/m/book-1.html'>Fixture</a></div></li></ul>"
                        }
                        else -> when (path) {
                            "/novel/detail/1" -> "<h1 class='detail-page__title'>Fixture</h1><img class='detail-page__poster-img' data-src='/cover'><a class='detail-page__catalog-item' href='/novel_chapter/1/1.html'><span class='detail-page__chapter-title'>One</span></a>"
                            "/novel_chapter/1/1.html" -> "<main><div class='article'><p>First chapter.</p></div></main>"
                            else -> "<a href='/novel/all/fantasy'>Category</a><ul class='dx-novel-list'><li><h2>Fixture</h2><a href='/novel/detail/1'>Book</a></li></ul>"
                        }
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8").setBody(html)
                }
            }
            fixture.source(customize = { definition }).use { source ->
                val catalogue = source.openDiscovery("fixture").catalog()
                assertTrue("source $index catalogue", catalogue.rows.size in 2..32)
                assertEquals(1, source.discovery(catalogue.rows.first().url).size)
                val book = source.search("fixture").single()
                val info = source.information(book.id)
                assertEquals("Fixture", info.title)
                val chapters = source.directory(book.id)
                assertEquals(if (index == 2) 1 else 0, chapters.count { it.isVolume })
                val content = source.content(book.id, chapters.first { !it.isVolume }.id)
                assertTrue(content.parts.any { it.text?.contains("First chapter.") == true })
                assertArrayEquals(plain, source.image(book.id, info.coverUrl, true))
                if (index == 1) {
                    val request = checkNotNull(posted)
                    assertEquals("POST", request.method)
                    assertEquals("${local}novel1/chapter0.html", request.getHeader("Referer"))
                    assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
                    assertTrue(request.body.readUtf8().contains("novel=1"))
                }
                if (index == 2) assertTrue(checkNotNull(nonceRequest).contains("id=1&v=fixture"))
            }
        }
    }
}
