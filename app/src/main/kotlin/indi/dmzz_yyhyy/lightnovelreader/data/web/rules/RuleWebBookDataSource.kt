package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.net.Uri
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import hnovel.content.*
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.error.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.image.SourceImageProvider
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime

/** Converts rule data once; repositories, readers and workers consume their existing source-bound contracts. */
internal class RuleWebBookDataSource(override val id: Identifier, private val source: RuleSource) :
    WebBookDataSource by EmptyWebDataSource, SourceImageProvider, AutoCloseable {
    override val permits = 1
    override val offLine = false
    override val isOffLineFlow = MutableStateFlow(false)
    override suspend fun isOffLine() = false
    override val discoveryProvider = RuleDiscoveryProvider(source)
    override val searchProvider = object : SearchProvider {
        override val searchTypes = if (source.canSearch) listOf(SearchType("keyword", LocalString(R.string.sources_search_type), LocalString(R.string.sources_search_hint))) else emptyList()
        override fun search(searchType: SearchType, keyword: String) = flow {
            val seen = mutableSetOf<String>()
            for (page in 1..64) {
                val result = request { source.search(keyword, page) }
                result.onErr { emit(SearchResult.Error(it.throwable ?: IllegalStateException(it.message))) }
                if (result.isErr) return@flow
                var added = false
                result.onOk { books -> books.filter { seen.add(it.id) }.forEach {
                    added = true
                    emit(SearchResult.MultipleBook(it.id))
                } }
                if (!added) {
                    if (seen.isEmpty()) emit(SearchResult.Empty())
                    emit(SearchResult.End())
                    return@flow
                }
            }
            emit(SearchResult.Error(SourceContentException(ContentError.Limit, "ruleSearch")))
        }
    }
    override suspend fun getBookInformation(id: String) = request {
        source.information(id).let { book -> BookInformation(book.id, book.title, author = book.author,
            description = book.description, coverUri = if (book.coverUrl.isBlank()) Uri.EMPTY else Uri.parse(book.coverUrl),
            tags = book.tags, publishingHouse = "", wordCount = WordCount(book.wordCount.toIntOrNull() ?: 0),
            lastUpdated = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(book.observedUpdate), java.time.ZoneOffset.UTC), isComplete = false) }
    }
    override suspend fun getBookVolumes(id: String) = request {
        val volumes = mutableListOf<Volume>()
        var volumeId = "default"; var title = ""; var chapters = mutableListOf<ChapterInformation>()
        fun finish() { if (chapters.isNotEmpty()) volumes += Volume(volumeId, title, chapters.toList()) }
        for (chapter in source.directory(id)) {
            if (chapter.isVolume) { finish(); volumeId = chapter.id; title = chapter.title; chapters = mutableListOf() }
            else chapters += ChapterInformation(chapter.id, chapter.title)
        }
        finish()
        BookVolumes(id, volumes)
    }
    override suspend fun getChapterContent(chapterId: String, bookId: String) = request {
        val chapter = source.content(bookId, chapterId)
        val builder = ContentBuilder()
        chapter.parts.forEach { part ->
            part.text?.let { builder.component(SimpleTextComponentData(it)) }
            part.image?.let { builder.component(ImageComponentData(Uri.parse(it))) }
        }
        ChapterContent(chapter.id, chapter.title, builder.build(), chapter.previous, chapter.next)
    }
    override suspend fun getImage(bookId: String, url: String, cover: Boolean) = request { source.image(bookId, url, cover) }
    override fun close() = source.close()
    private suspend fun <T> request(block: suspend () -> T): Result<T, WebRequestError> = try { Ok(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SourceContentException) { Err(WebRequestError("Source request failed", error.message.orEmpty(), error,
        when (error.code) {
            ContentError.LoginRequired -> WebRequestErrorKind.AuthenticationRequired
            ContentError.Unavailable -> WebRequestErrorKind.SourceUnavailable
            else -> WebRequestErrorKind.Other
        })) }
}
