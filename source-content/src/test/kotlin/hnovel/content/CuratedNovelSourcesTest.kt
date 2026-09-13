package hnovel.content

import hnovel.imports.SourceDefinitionImporter
import hnovel.imports.SourceDefinitionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.nio.file.Files
import java.net.URI

class CuratedNovelSourcesTest {
    private val definitions = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/curated-sources/novels-v1.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonArray

    @Test fun curatedDefinitionsImportAsDistinctTextSitesOutsideTheKnownPackageCollection() {
        val root = Files.createTempDirectory("curated-import")
        try {
            val preview = SourceDefinitionImporter(SourceDefinitionStore(root)).preview(definitions.toString())
            assertTrue(preview.issues.toString(), preview.issues.isEmpty())
            assertEquals(3, preview.candidates.size)
            val hosts = definitions.map { URI(it.jsonObject.getValue("bookSourceUrl").jsonPrimitive.content).host }.toSet()
            assertEquals(definitions.size, hosts.size)
            val known = listOf("hoohoo-1.0.3-4.json", "potato-1.0-1.json").flatMap { file ->
                Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/known-sources/$file"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonArray
            }
            assertTrue(known.none { URI(it.jsonObject.getValue("bookSourceUrl").jsonPrimitive.content).host in hosts })
            assertTrue(definitions.all { it.jsonObject.getValue("bookSourceType").jsonPrimitive.int == 0 })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun fanqieFiltersAudioAndLockedChaptersAndDecodesNestedTextWithinTheBudget() = verify(0)
    @Test fun sfExposesPublicChaptersWithoutRepeatingTheOnlySearchPage() = verify(1)
    @Test fun kanunuDecodesGbkAndKeepsRecommendationsOutsideTheSelectedBook() = verify(2)
    @Test fun fanqieRejectsUnknownFontVersions() = verify(0, unknownFont = true)
    @Test fun fanqieRejectsUnmappedPrivateUseCharacters() = verify(0, unknownGlyph = true)

    private fun verify(index: Int, unknownFont: Boolean = false, unknownGlyph: Boolean = false) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = definitions[index].jsonObject
            val local = fixture.server.url("/").toString()
            var text = original.toString()
            for (origin in listOf("https://fanqienovel.com/", "https://novel.snssdk.com/", "https://book.sfacg.com/",
                "https://m.sfacg.com/", "https://www.kanunu8.com/")) {
                text = text.replace(origin, local).replace(origin.removeSuffix("/"), local.removeSuffix("/"))
            }
            val definition = Json.parseToJsonElement(text).jsonObject
            val expectedTitle = if (index == 2) "西游记" else "Fixture"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    val body = when (index) {
                        0 -> when {
                            path.startsWith("/api/novel/") -> """{"code":0,"data":{"ret_data":[{"title":"Fixture","author":"Writer","book_id":"1","genre":"0"},{"title":"Audio","book_id":"2","genre":"4"}]}}"""
                            path == "/page/1" -> "<link rel='canonical' href='${local}page/1'><script>${" ".repeat(220000)}</script><h1>Fixture</h1><span class='author-name-text'>Writer</span>"
                            path.startsWith("/api/reader/") -> """{"code":0,"data":{"chapterListWithVolume":[[{"itemId":"1","title":"One","needPay":0,"isChapterLock":false},{"itemId":"2","title":"Paid","needPay":1},{"itemId":"3","title":"Locked","isChapterLock":true}]]}}"""
                            path == "/reader/1" -> "<style>body{font-family:${"other".repeat(350)};}@font-face{src:url(dc027189e0ba4cd.woff);${if (unknownFont) "src:url(new-font-version.woff);" else ""}}</style>" +
                                "<div class='muye-reader-content'><p>First chapter.<b>\ue3e9</b>${"\ue3e9".repeat(3200)}${if (unknownGlyph) "\ue000" else ""}正文</p><img src='/ignored'></div>"
                            else -> return MockResponse().setResponseCode(404)
                        }
                        1 -> when {
                            path.startsWith("/API/") -> """{"Status":200,"Novels":[{"NovelID":1,"NovelName":"Fixture","AuthorName":"Writer"}]}"""
                            path == "/Novel/1/" -> "<h1 class='title'><span class='text'>Fixture<span class='tag blue'>VIP</span><span class='tag green'>Contest</span></span></h1><span class='author-name'>Writer</span>"
                            path == "/i/1/" -> "<ul class='mulu_list'><a href='/c/1/'><li>One</li></a><a href='/c/2/'><li>Paid<span class='icon-lock2'></span></li></a></ul>"
                            path == "/c/1/" -> "<div class='yuedu Content_Frame'><p>First chapter.<b>正文</b></p><img src='/ignored'></div>"
                            else -> return MockResponse().setResponseCode(404)
                        }
                        else -> when {
                            path == "/files/old/" -> "<a href='/files/old/2011/2448.html'>吴承恩: 西游记</a>" +
                                "<a href='/files/old/2011/2448.html'>吴承恩: 西游记</a><a href='/files/old/2011/2447.html'>罗贯中: 三国演义</a>" +
                                "<a href='/files/old/2011/2000.html'>非小说: 排除</a>" +
                                (1..200).joinToString("") { "<a href='/unrelated/$it'>Other category $it</a>" }
                            path.endsWith("/2448.html") -> "<title>西游记 - 小说在线阅读 - 吴承恩 - 努努书坊</title><h2>西游记</h2>" +
                                "<table><tr><td><a href='2448/1.html'>One</a><a href='2447/2.html'>Other book</a></td></tr></table>"
                            path == "/files/old/2011/2448/1.html" -> "<nav>Exclude navigation</nav><table><tr><td width='820'><p>First chapter.<b>正文</b></p></td></tr></table>"
                            else -> return MockResponse().setResponseCode(404)
                        }
                    }
                    return if (index == 2) MockResponse().setHeader("Content-Type", "text/html")
                        .setBody(okio.Buffer().write(("<meta charset='gb18030'>" + body).toByteArray(Charset.forName("GB18030"))))
                    else MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8").setBody(body)
                }
            }
            fixture.source(customize = { definition }).use { source ->
                val book = source.search(if (index == 2) "西游" else "fixture").single()
                assertEquals(expectedTitle, book.title)
                if (index != 0) assertTrue(source.search("fixture", 2).isEmpty())
                if (index == 2) {
                    val catalogue = source.openDiscovery("fixture").catalog()
                    assertEquals(2, source.discovery(catalogue.rows.single().url).size)
                }
                val info = source.information(book.id)
                assertEquals(expectedTitle, info.title)
                assertEquals(if (index == 2) "吴承恩" else "Writer", info.author)
                val chapters = source.directory(book.id)
                assertEquals(1, chapters.size)
                assertEquals(local + when (index) { 0 -> "reader/1"; 1 -> "c/1/"; else -> "files/old/2011/2448/1.html" }, chapters.single().id)
                if (unknownFont || unknownGlyph) {
                    val failure = runCatching { source.content(book.id, chapters.single().id) }.exceptionOrNull()
                    assertTrue(failure.toString(), failure is SourceContentException)
                    assertEquals(ContentError.InvalidRule, (failure as SourceContentException).code)
                    assertEquals("ruleContent.content", failure.field)
                } else {
                    val content = source.content(book.id, chapters.single().id)
                    val plain = content.parts.joinToString("") { it.text.orEmpty() }
                    assertTrue(plain, plain.contains("First chapter.") && plain.contains("正文"))
                    assertFalse(plain.contains("Exclude navigation"))
                    assertTrue(content.parts.all { it.text != null })
                    assertFalse(plain.any { it.code in 0xe000..0xf8ff || it == '\ufffd' })
                    if (index == 0) assertEquals(3201, plain.count { it == '在' })
                }
            }
        }
    }
}
