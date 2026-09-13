package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class HtmlCharsetContentTest {
    @Test fun gbkResponsesReachSearchInformationDirectoryAndChapterText() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val html = when (request.path!!.substringBefore('?')) {
                        "/search" -> "<li><a href='/book/one'><h2>校园小说</h2></a><b>作者</b></li>"
                        "/book/one" -> "<h1>校园小说</h1><b>作者</b><a class='toc' href='/toc/1'>目录</a>"
                        "/toc/1" -> "<li><a href='/c/1'>第一章</a></li>"
                        "/c/1" -> "<article><p>第一段正文。</p><p>第二段正文。</p></article>"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(okio.Buffer().write(
                        ("<head><meta http-equiv='Content-Type' content='text/html; charset=GBK'></head>" + html)
                            .toByteArray(Charset.forName("GBK"))))
                }
            }
            fixture.source(customize = { definition -> JsonObject(definition + mapOf(
                "ruleContent" to buildJsonObject { put("content", "article@html") }
            )) }).use { source ->
                val book = source.search("校园").single()
                assertEquals("校园小说", book.title)
                val info = source.information(book.id)
                assertEquals("校园小说", info.title)
                assertEquals("作者", info.author)
                val chapter = source.directory(book.id).single()
                assertEquals("第一章", chapter.title)
                val text = source.content(book.id, chapter.id).parts.mapNotNull { it.text }.joinToString("\n")
                assertTrue(text, text.contains("第一段正文。") && text.contains("第二段正文。"))
                assertFalse(text.contains('\ufffd'))
            }
        }
    }
}
