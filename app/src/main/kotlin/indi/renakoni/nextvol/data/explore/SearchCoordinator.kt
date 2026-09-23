package indi.renakoni.nextvol.data.explore

import com.github.michaelbull.result.getOrElse
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

internal const val SEARCH_RESULT_LIMIT = 1000
internal data class SearchRequest(val source: Identifier, val page: Int)
internal data class SearchBatch(
    val request: SearchRequest,
    val books: List<SearchResult.MultipleBook> = emptyList(),
    val complete: Boolean = false,
    val nextPage: Int? = null,
    val failure: SourceSearchFailure? = null,
    val limited: Boolean = false,
)

/** Searches one page per source. Both worker count and in-flight requests are bounded. */
@Singleton
class SearchCoordinator internal constructor(private val explore: ExploreRepository, private val io: CoroutineDispatcher) {
    @Inject constructor(explore: ExploreRepository) : this(explore, Dispatchers.IO)

    // Shared across runs, so replacement queries wait for cancelled requests to release their permits.
    private val gate = Semaphore(4)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun search(keyword: String, requests: List<SearchRequest>, query: String? = null): Flow<SearchBatch> = requests.asFlow()
        .flatMapMerge(concurrency = 4) { request ->
            flow { gate.withPermit { emitAll(load(keyword, request, query)) } }.flowOn(io)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun load(keyword: String, request: SearchRequest, query: String?): Flow<SearchBatch> = flow {
        val pending = mutableListOf<SearchResult.MultipleBook>()
        val seen = hashSetOf<String>()
        var failure: SourceSearchFailure? = null
        var next: Int? = null
        var limited = false
        try {
            withTimeout(30_000) {
                val session = explore.open(request.source).getOrElse {
                    failure = it
                    return@withTimeout
                }
                if (session.hasPages) {
                    val page = session.page(session.types.first(), keyword, request.page, query).first()
                    pending += page.books.take(SEARCH_RESULT_LIMIT)
                    limited = page.books.size > SEARCH_RESULT_LIMIT
                    next = page.nextPage
                    if (next != null && next <= request.page) {
                        next = null
                        failure = SourceSearchFailure(DiscoveryError.InvalidResponse)
                    }
                } else {
                    // Legacy plugins have no page cursor. Consume their stream once, retaining partial
                    // results on failure and stopping even if the provider ignores its terminal event.
                    session.search(session.types.first(), keyword).buffer(0).transformWhile { event ->
                        emit(event)
                        event is SearchResult.MultipleBook
                    }.collect { event ->
                        val book = when (event) {
                            is SearchResult.MultipleBook -> event
                            is SearchResult.SingleBook -> SearchResult.MultipleBook(event.bookId)
                            else -> null
                        }
                        if (book != null && seen.add(book.bookId)) pending += book
                        if (event is SearchResult.Error) failure = searchFailure(event.error)
                        if (pending.size >= 16 || seen.size == 1 && pending.isNotEmpty()) {
                            emit(SearchBatch(request, pending.toList()))
                            pending.clear()
                        }
                        if (seen.size >= SEARCH_RESULT_LIMIT) throw ResultLimitReached()
                    }
                }
            }
        } catch (_: ResultLimitReached) {
            limited = true
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = SourceSearchFailure(DiscoveryError.Network)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure = searchFailure(error)
        }
        emit(SearchBatch(request, pending.toList(), complete = true, nextPage = next, failure = failure, limited = limited))
    }

    private class ResultLimitReached : RuntimeException()
}
