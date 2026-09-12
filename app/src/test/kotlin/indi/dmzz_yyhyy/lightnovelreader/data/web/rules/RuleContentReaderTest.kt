package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentTestHost
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleContentReaderTest {
    @Test fun normalChapterReachesTheHostReaderWithTextAndImagesInOrder() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val paragraphs = List(80) { "第 $it 段 " + "正文内容。".repeat(16) }
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setBody(paragraphs.mapIndexed { index, text ->
                        (if (index == 40) "<img src='../image.png'>" else "") + "<p>$text</p>"
                    }.joinToString("", "<article>", "</article>")) else normal.dispatch(request)
            }
            val source = fixture.source(customize = { raw -> JsonObject(raw +
                ("ruleContent" to buildJsonObject { put("content", "article@html") })) })
            RuleWebBookDataSource(Identifier("rules", source.definition.sourceId), source).use { provider ->
                val book = fixture.server.url("/book/one").toString()
                val chapter = provider.getBookVolumes(book).get()!!.volumes.single().chapters.first()
                val result = provider.getChapterContent(chapter.id, book)
                assertTrue(result.toString(), result.isOk)
                val content = result.get()!!
                val host = ContentTestHost().apply { initializeInjector() }
                val components = host.renderer.getContentDataFromJson(content.content).components
                assertEquals(paragraphs, components.mapNotNull { (it.data as? SimpleTextComponentData)?.text })
                assertEquals(81, components.size)
                assertEquals(fixture.server.url("/image.png").toString(), (components[40].data as ImageComponentData).uri.toString())
                assertEquals(chapter.id, content.id)
                assertEquals(chapter.title, content.title)
            }
        }
    }
}
