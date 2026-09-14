package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class InlineSourceTest {
    private fun data(value: String) = "data:;base64," + Base64.getEncoder().encodeToString(value.toByteArray()) + ",{\"type\":\"fixture\"}"

    @Test fun distinctInlinePagesDoNotLookLikeRepeatedLocalhostRedirects(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val second = data("""{"items":[{"title":"Two","url":"${fixture.server.url("/c/2")}"}],"next":""}""")
            val first = data(buildJsonObject {
                put("items", buildJsonArray { add(buildJsonObject {
                    put("title", "One"); put("url", fixture.server.url("/c/1").toString())
                }) }); put("next", second)
            }.toString())
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                    ("tocUrl" to JsonPrimitive("@js:" + JsonPrimitive(first)))),
                "ruleToc" to buildJsonObject {
                    put("chapterList", "<js>java.hexDecodeToString(result)</js>$.items[*]")
                    put("chapterName", "$.title"); put("chapterUrl", "$.url")
                    put("nextTocUrl", "<js>java.hexDecodeToString(result)</js>$.next")
                }
            )) }).use { source ->
                assertEquals(listOf("One", "Two"), source.directory(fixture.server.url("/book/one").toString()).map { it.title })
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun inlineDirectoryAndChapterInputsSurvivePersistenceAndRunApiRules(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val toc = data(fixture.server.url("/toc/1").toString())
            val chapter = data(fixture.server.url("/c/2").toString())
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "bookSourceComment" to JsonPrimitive(" original\ncomment "),
                "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                    ("tocUrl" to JsonPrimitive("@js:" + JsonPrimitive(toc)))),
                "ruleToc" to buildJsonObject {
                    put("chapterList", """<js>
                        if (baseUrl !== ${JsonPrimitive(toc)}) throw new Error('lost logical directory URL');
                        java.ajax(java.hexDecodeToString(result))</js>li""")
                    put("chapterName", "a@text"); put("chapterUrl", "@js:" + JsonPrimitive(chapter))
                    put("isVolume", "@data-volume")
                },
                "ruleContent" to buildJsonObject {
                    put("content", """<js>
                        if (baseUrl !== ${JsonPrimitive(chapter)}) throw new Error('lost logical chapter URL');
                        if (chapter.isVip() || chapter.isPay() || chapter.isVolume()) throw new Error('invalid flags');
                        if (source.bookSourceComment !== ' original\ncomment ') throw new Error('comment changed');
                        java.ajax(java.hexDecodeToString(result))
                        </js>article@html""")
                }
            )) }).use { source ->
                val book = source.search("title").single()
                assertEquals(toc, source.information(book.id).tocUrl)
                val chapters = source.directory(book.id).filterNot { it.isVolume }
                assertEquals(1, chapters.size)
                assertEquals(chapter, chapters.single().id)
                val text = source.content(book.id, chapter).parts.mapNotNull { it.text }.joinToString("\n")
                assertEquals("second chapter", text)
                val paths = (0 until fixture.server.requestCount).map { fixture.server.takeRequest().path?.substringBefore('?') }
                assertEquals(listOf("/search", "/book/one", "/toc/1", "/toc/1", "/c/2"), paths)
            }
        }
    }
}
