package indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary

import android.content.Context
import android.net.Uri
import com.github.michaelbull.result.*
import hnovel.network.SourceSession
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.image.SourceImageProvider
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import java.time.LocalDateTime

/** Search and metadata only. Registry capabilities deliberately exclude directory and full text. */
internal class ZLibrarySource(private val context: Context, private val session: SourceSession,
    private val client: ZLibraryClient) : WebBookDataSource by EmptyWebDataSource, SourceImageProvider, AutoCloseable {
    override val id = ZLibrarySources.ID
    override val permits = 3
    override val cache = Cache(256, 5 * 60 * 1000)
    override val offLine = false
    override val isOffLineFlow = MutableStateFlow(false)
    override suspend fun isOffLine() = false
    override val searchProvider = object : SearchProvider {
        override val searchTypes = modes.map { SearchType(it.id, LocalString(it.label), LocalString(R.string.zlibrary_search_hint)) }
        override fun search(searchType: SearchType, keyword: String) = flow {
            val mode = modes.find { it.id == searchType.type }
            if (mode == null) { emit(SearchResult.Error(SourceRequestException(DiscoveryError.InvalidRequest))); return@flow }
            val seen = mutableSetOf<String>()
            var page = 1
            repeat(25) {
                val response = client.search(keyword, page, mode.language, mode.format)
                var added = false
                response.books.forEach { book ->
                    currentCoroutineContext().ensureActive()
                    if (seen.add(book.id)) { added = true; emit(SearchResult.MultipleBook(book.id)) }
                }
                if (response.next == null) {
                    if (seen.isEmpty()) emit(SearchResult.Empty())
                    emit(SearchResult.End())
                    return@flow
                }
                if (!added) throw SourceRequestException(DiscoveryError.InvalidResponse)
                page = response.next
            }
            // Bound one query to 500 results; never silently call a partial result set complete.
            throw SourceRequestException(DiscoveryError.Limit)
        }.catch { failure ->
            // Flow.catch preserves downstream cancellation/take() and exception transparency.
            currentCoroutineContext().ensureActive()
            emit(SearchResult.Error(failure))
        }
    }

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> = request {
        val book = client.information(id)
        BookInformation(book.id, book.title, subtitle = listOf(book.language, book.format, book.year, book.size)
            .filter(String::isNotBlank).joinToString(" · "), author = book.author, description = book.description,
            coverUri = if (book.cover.isBlank()) Uri.EMPTY else Uri.parse(book.cover),
            tags = listOf(book.language, book.format).filter(String::isNotBlank), publishingHouse = book.publisher,
            wordCount = WordCount(0), lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
    }

    override suspend fun getImage(bookId: String, url: String, cover: Boolean) = request { client.image(url) }
    override fun close() = session.close()

    private suspend fun <T> request(block: suspend () -> T): Result<T, WebRequestError> = try { Ok(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SourceRequestException) {
        Err(WebRequestError("Z-Library", context.getString(sourceFailureMessage(failure.error)), failure,
            if (failure.error == DiscoveryError.AuthenticationRequired) WebRequestErrorKind.AuthenticationRequired else WebRequestErrorKind.Other))
    }

    private data class Mode(val id: String, val label: Int, val language: String? = null, val format: String? = null)
    companion object { private val modes = listOf(
        Mode("all", R.string.zlibrary_filter_all), Mode("chinese", R.string.zlibrary_filter_chinese, "chinese"),
        Mode("english", R.string.zlibrary_filter_english, "english"), Mode("epub", R.string.zlibrary_filter_epub, format = "EPUB"),
        Mode("pdf", R.string.zlibrary_filter_pdf, format = "PDF"),
        Mode("chinese-epub", R.string.zlibrary_filter_chinese_epub, "chinese", "EPUB"),
        Mode("chinese-pdf", R.string.zlibrary_filter_chinese_pdf, "chinese", "PDF"),
        Mode("english-epub", R.string.zlibrary_filter_english_epub, "english", "EPUB"),
        Mode("english-pdf", R.string.zlibrary_filter_english_pdf, "english", "PDF"),
    ) }
}
