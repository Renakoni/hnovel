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
    fun bothEntryPointsPreserveProcessorOrderMetadataExtensionsAndIncompleteItems() = runTest {
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
            "metadata":"root-extension",
            "components":[
                {"id":"lightnovelreader:simple_text","data":{"text":"body"},"extra":1},
                {"id":"simple_text","data":{"text":"short"}},
                {"id":"fixture:unknown","data":{"value":2}},
                {"data":{"text":"missing id"}},
                {"id":"fixture:missing-data"},
                7,
                []
            ]
        }""").jsonObject, "previous", "next")
        val expected = chapter.copy(content = Json.parseToJsonElement("""{
            "metadata":"root-extension",
            "components":[
            {"id":"lightnovelreader:simple_text","data":{"text":"bodyAB"},"extra":1},
            {"id":"simple_text","data":{"text":"short"}},
            {"id":"fixture:unknown","data":{"value":2}},
            {"data":{"text":"missing id"}},
            {"id":"fixture:missing-data"},
            7,
            []
        ]}""").jsonObject)

        val decoder = ContentJsonDecoder(registry)
        val outputs = listOf(
            processing.processChapterContent("book") { chapter },
            processing.coroutineProcessChapterContent("book") { chapter },
        )
        for (output in outputs) {
            assertEquals(expected, output)
            val rendered = decoder.decodeComponents(
                output.content,
                create = { _, _, decode -> (decode() as SimpleTextComponentData).text },
                error = { "error" },
            )
            assertEquals(listOf("bodyAB", "short", "error", "error", "error", "error", "error"), rendered)
            val exported = mutableListOf<String>()
            decoder.getDataFromJsonObject(output.content) { exported += (it as SimpleTextComponentData).text }
            assertEquals(listOf("bodyAB"), exported)
        }
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

    @Test
    fun missingOrNonArrayComponentsLeaveTheRootJsonUnchanged() {
        val registry = ContentComponentRegistry()
        for (input in listOf("""{"metadata":"missing"}""", """{"metadata":"wrong","components":{}}""")) {
            val original = Json.parseToJsonElement(input).jsonObject
            val processor = ComponentProcessor(registry.serializeMap, registry.dataKClassMap, original)

            processor.process<SimpleTextComponentData> { it.copy(text = it.text + "changed") }

            assertEquals(original, processor.get())
            val decoder = ContentJsonDecoder(registry)
            assertEquals(
                listOf("error"),
                decoder.decodeComponents(processor.get(), { _, _, _ -> "unexpected" }, { "error" }),
            )
            decoder.getDataFromJsonObject(processor.get()) { error("No valid component to export") }
        }
    }
}
