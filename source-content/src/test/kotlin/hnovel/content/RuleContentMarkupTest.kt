package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RuleContentMarkupTest {
    @Test fun fortyParagraphChapterUsesProductionLimits() = chapter(40)
    @Test fun eightyParagraphChapterKeepsTextAndImageOrder() = chapter(80)
    @Test fun longChapterUsesProductionLimits() = chapter(400)

    @Test fun jsonChapterRetainsNewlinesAndInlineImagePosition() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setHeader("Content-Type", "application/json").setBody(
                        buildJsonObject { put("content", "\u3000\u3000First &amp; paragraph.\r\n\r\nSecond paragraph.\n" +
                            "<img src='../image.png'>\nThird paragraph.") }.toString())
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "$.content") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf(ContentPart(text = "First & paragraph."), ContentPart(text = "Second paragraph."),
                    ContentPart(image = fixture.server.url("/image.png").toString()), ContentPart(text = "Third paragraph.")),
                    source.content(book.id, chapter.id).parts)
            }
        }
    }

    @Test fun textNodesChapterRetainsParagraphBoundaries() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setBody("<article>First paragraph.<br>Second paragraph.<br>Third paragraph.</article>")
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "article@textNodes") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf("First paragraph.", "Second paragraph.", "Third paragraph."),
                    source.content(book.id, chapter.id).parts.mapNotNull { it.text })
            }
        }
    }

    private fun chapter(count: Int) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val paragraphs = List(count) { "Paragraph $it " + "a".repeat(80) }
            val middle = count / 2
            val html = paragraphs.mapIndexed { index, text ->
                (if (index == middle) "<img src='../image.png'>" else "") + "<p>$text</p>"
            }.joinToString("")
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setBody("<article>$html</article>")
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "article@html") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                val content = source.content(book.id, chapter.id)
                assertEquals(paragraphs, content.parts.mapNotNull { it.text })
                assertEquals(count + 1, content.parts.size)
                assertEquals(ContentPart(image = fixture.server.url("/image.png").toString()), content.parts[middle])
                assertEquals(chapter.id, content.id)
                assertEquals(chapter.title, content.title)
            }
        }
    }
}
