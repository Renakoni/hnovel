package indi.renakoni.nextvol.tts

import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.content.ComponentDataRegistry
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

fun interface SpeechChapterSource {
    suspend fun load(bookId: String, chapterId: String): SpeechChapter
}

/** Uses processed source content, independently of pagination, UI subscriptions and reading records. */
class RepositorySpeechChapterSource @Inject constructor(
    private val chapters: ChapterSource,
    private val components: ComponentDataRegistry,
    private val local: LocalBookDataSource,
) : SpeechChapterSource {
    override suspend fun load(bookId: String, chapterId: String): SpeechChapter =
        withContext(Dispatchers.IO + ForegroundSourceRequest(allowsInteraction = false)) {
            val book = BookIdentity.book(bookId)
            val requested = BookIdentity.chapter(chapterId, book)
            // A cached first emission is sufficient. Do not refresh a chapter already being spoken.
            val chapter = chapters.getChapterContentFlow(requested.storageKey, book.storageKey)
                .first().getOrElse { throw SpeechException(SpeechError.SourceUnavailable) }
            if (BookIdentity.chapter(chapter.id, book) != requested) {
                throw SpeechException(SpeechError.SourceUnavailable)
            }
            SpeechChapter(
                book.storageKey, requested.storageKey,
                local.getBookInformation(book.storageKey)?.title.orEmpty(), chapter.title,
                speechText(chapter.content, components),
                chapter.prevChapter?.let { BookIdentity.chapter(it, book).storageKey },
                chapter.nextChapter?.let { BookIdentity.chapter(it, book).storageKey },
            )
        }
}

internal fun speechText(content: JsonObject, registry: ComponentDataRegistry): String {
    val array = content["components"] as? JsonArray ?: throw SpeechException(SpeechError.UnsupportedContent)
    val serializers = registry.serializeMap
    val parts = array.mapIndexedNotNull { index, element ->
        val component = element as? JsonObject ?: throw SpeechException(SpeechError.UnsupportedContent)
        val rawId = (component["id"] as? JsonPrimitive)?.content ?: throw SpeechException(SpeechError.UnsupportedContent)
        val id = if (':' in rawId) rawId else "lightnovelreader:$rawId"
        val data = component["data"] as? JsonObject ?: throw SpeechException(SpeechError.UnsupportedContent)
        val serializer = serializers[id] ?: throw SpeechException(SpeechError.UnsupportedContent)
        when (val decoded = serializer.fromJsonElement(data)) {
            is SimpleTextComponentData -> index to decoded.text
            is ImageComponentData -> null
            else -> throw SpeechException(SpeechError.UnsupportedContent)
        }
    }
    val text = SpeechTextIndex(parts).text
    if (text.isBlank()) throw SpeechException(SpeechError.EmptyText)
    return text
}
