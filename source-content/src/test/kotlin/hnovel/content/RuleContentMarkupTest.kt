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
