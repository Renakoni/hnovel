package indi.renakoni.nextvol.data.web.rules

import android.net.Uri
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.getOrElse
import hnovel.content.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.explore.PagedSearchProvider
import indi.renakoni.nextvol.data.explore.SearchPage
import indi.renakoni.nextvol.data.web.EmptyWebDataSource
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
internal class RuleWebBookDataSource(override val id: Identifier, private val source: RuleSource,
    private val recovery: RuleRequestRecovery? = null) :
    WebBookDataSource by EmptyWebDataSource, SourceImageProvider, AutoCloseable {
    override val permits = 1
    override val offLine = false
    override val isOffLineFlow = MutableStateFlow(false)
    override suspend fun isOffLine() = false
    override val discoveryProvider = RuleDiscoveryProvider(source, recovery = recovery)
    override val searchProvider: SearchProvider = object : SearchProvider, PagedSearchProvider {
        private val queries = object : LinkedHashMap<Pair<String, String>, RuleListSession>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, RuleListSession>) = size > 8
        }
        override val searchTypes = if (source.canSearch) listOf(SearchType("keyword", LocalString(R.string.sources_search_type), LocalString(R.string.sources_search_hint))) else emptyList()
        override suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String?): SearchPage {
            val pager = if (query == null) source.openSearchPages(keyword) else synchronized(queries) {
                queries.getOrPut(query to keyword) { source.openSearchPages(keyword) }
            }
            val result = request { pager.page(page) }.getOrElse {
                throw (it.throwable ?: SourceContentException(ContentError.Unavailable, "ruleSearch"))
            }
            return SearchPage(result.books.map { SearchResult.MultipleBook(it.id, it.information()) }, result.nextPage)
        }
        override fun search(searchType: SearchType, keyword: String) = flow {
            val seen = mutableSetOf<String>()
            val pager = source.openSearchPages(keyword)
            for (page in 1..64) {
                val result = request { pager.page(page) }
                result.onErr { emit(SearchResult.Error(it.throwable ?: IllegalStateException(it.message))) }
                if (result.isErr) return@flow
                result.onOk { data -> data.books.filter { seen.add(it.id) }.forEach {
                    emit(SearchResult.MultipleBook(it.id, it.information()))
                } }
                if (result.getOrElse { return@flow }.nextPage == null) {
                    if (seen.isEmpty()) emit(SearchResult.Empty())
                    emit(SearchResult.End())
                    return@flow
                }
            }
            emit(SearchResult.Error(SourceContentException(ContentError.Limit, "ruleSearch")))
        }
    }
    internal suspend fun canonicalBookId(id: String) = source.canonicalBookId(id)

    override suspend fun getBookInformation(id: String) = request {
        // The legacy source API keeps the caller's remote ID; migration is a separate host operation.
        source.information(id).information().copy(id = id)
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
    private fun RuleBook.information() = BookInformation(id, title, author = author,
        description = description, coverUri = if (coverUrl.isBlank()) Uri.EMPTY else Uri.parse(coverUrl),
        tags = tags, publishingHouse = "", wordCount = WordCount(wordCount.toIntOrNull() ?: 0),
        lastUpdated = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(observedUpdate), java.time.ZoneOffset.UTC), isComplete = false)
    override fun close() = source.close()
    private suspend fun <T> request(block: suspend () -> T): Result<T, WebRequestError> = try {
        Ok(if (recovery == null) block() else recovery.execute(block))
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SourceContentException) { Err(WebRequestError("Source request failed", error.message.orEmpty(), error,
        when (error.code) {
            ContentError.LoginRequired -> WebRequestErrorKind.AuthenticationRequired
            ContentError.BrowserRequired -> if (error.verification?.kind == hnovel.network.BrowserChallengeKind.Login)
                WebRequestErrorKind.AuthenticationRequired else WebRequestErrorKind.VerificationRequired
            ContentError.Unavailable -> WebRequestErrorKind.SourceUnavailable
            else -> WebRequestErrorKind.Other
        })) }
}
