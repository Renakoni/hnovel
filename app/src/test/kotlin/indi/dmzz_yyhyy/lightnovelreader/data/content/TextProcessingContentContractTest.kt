package indi.dmzz_yyhyy.lightnovelreader.data.content

import android.app.Application
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class TextProcessingContentContractTest {
    @Test
    fun bothEntryPointsPreserveProcessorOrderJsonOutputAndChapterMetadataWithoutAnInjector() = runTest {
        val registry = ContentComponentRegistry()
        val processing = TextProcessingRepository(
            mockk { every { enabled } returns false },
            mockk { every { enabled } returns false },
            registry,
        )
        val events = mutableListOf<String>()
        fun processor(suffix: String) = object : TextProcessor {
            override val enabled = true
            override fun processText(text: String) = text + suffix
            override fun processChapterContent(bookId: String, chapterContent: ChapterContent, componentProcessor: ComponentProcessor): ChapterContent {
                events += "$bookId/$suffix"
                return super.processChapterContent(bookId, chapterContent, componentProcessor)
            }
        }
        processing.registerProcessors(Identifier("fixture", "first"), processor("A"))
        processing.registerProcessors(Identifier("fixture", "first"), processor("ignored"))
        processing.registerProcessors(Identifier("fixture", "second"), processor("B"))
        val chapter = ChapterContent("chapter", "title", Json.parseToJsonElement("""{
            "metadata":"currently discarded",
            "components":[
                {"id":"lightnovelreader:simple_text","data":{"text":"body"},"extra":1},
                {"id":"simple_text","data":{"text":"short"}},
                {"id":"fixture:unknown","data":{"value":2}},
                {"data":{"text":"missing id"}}
            ]
        }""").jsonObject, "previous", "next")
        val expected = chapter.copy(content = Json.parseToJsonElement("""{"components":[
            {"id":"lightnovelreader:simple_text","data":{"text":"bodyAB"}},
            {"id":"simple_text","data":{"text":"short"}},
            {"id":"fixture:unknown","data":{"value":2}}
        ]}""").jsonObject)

        assertEquals(expected, processing.processChapterContent("book") { chapter })
        assertEquals(expected, processing.coroutineProcessChapterContent("book") { chapter })
        assertEquals(listOf("book/A", "book/B", "book/A", "book/B"), events)
    }

    @Test
    fun registrationsAreReadAgainForEachProcessorRatherThanFrozenAtRepositoryConstruction() {
        val registry = ContentComponentRegistry()
        val processing = TextProcessingRepository(
            mockk { every { enabled } returns false }, mockk { every { enabled } returns false }, registry,
        )
        processing.registerProcessors(Identifier("fixture", "register"), object : TextProcessor {
            override val enabled = true
            override fun processText(text: String) = text
            override fun processChapterContent(bookId: String, chapterContent: ChapterContent, componentProcessor: ComponentProcessor): ChapterContent {
                registry.registrar.id(Identifier("fixture", "late"))
                    .component(fixtures.content.NoArgFixtureComponent::class)
                    .data(SimpleTextComponentData::class).serializer(SimpleTextComponentData.jsonSerializer).register()
                return chapterContent
            }
        })
        processing.registerProcessors(Identifier("fixture", "transform"), object : TextProcessor {
            override val enabled = true
            override fun processText(text: String) = text.uppercase()
        })
        val chapter = ChapterContent("chapter", "title", Json.parseToJsonElement("""{"components":[{"id":"fixture:late","data":{"text":"body"}}]}""").jsonObject)
        val expected = Json.parseToJsonElement("""{"components":[{"id":"fixture:late","data":{"text":"BODY"}}]}""").jsonObject
        assertEquals(expected, processing.processChapterContent("book") { chapter }.content)
    }
}
