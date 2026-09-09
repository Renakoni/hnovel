package indi.dmzz_yyhyy.lightnovelreader.data.web

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SourceDiscoveryTarget(val sourceId: Identifier, val target: String)
data class SourceDiscoveryBook(val id: SourceBookId, val title: String, val author: String, val coverUrl: String)
data class SourceDiscoverySection(val id: String, val title: String, val books: List<SourceDiscoveryBook>, val more: SourceDiscoveryTarget?)
data class SourceDiscoveryCategory(val id: String, val title: String, val target: SourceDiscoveryTarget)
data class SourceDiscoveryPage(val books: List<SourceDiscoveryBook>, val nextCursor: String?)

/** Captures one registration, never a UI selection or a replacement runtime. */
class SourceDiscovery internal constructor(private val runtime: SourceRuntime, private val provider: DiscoveryProvider) {
    val hasFeed get() = provider.hasFeed && SourceCapability.Explore in runtime.metadata.capabilities
    val hasCategories get() = provider.hasCategories && SourceCapability.Categories in runtime.metadata.capabilities

    suspend fun feed(): Result<List<SourceDiscoverySection>, DiscoveryError> = runtime.execute {
        if (!hasFeed) return@execute Err(DiscoveryError.Unsupported)
        provider.feed().map { sections -> sections.map {
            SourceDiscoverySection(it.id, it.title, it.books.map(::bind), it.more?.let(::target))
        } }
    }

    suspend fun categories(): Result<List<SourceDiscoveryCategory>, DiscoveryError> = runtime.execute {
        if (!hasCategories) return@execute Err(DiscoveryError.Unsupported)
        provider.categories().map { categories -> categories.map {
            SourceDiscoveryCategory(it.id, it.title, target(it.target))
        } }
    }

    fun open(target: SourceDiscoveryTarget): DiscoverySession {
        runtime.checkAvailable()
        require(target.sourceId == runtime.id) { "Discovery target belongs to another source" }
        return DiscoverySession(this, target.target)
    }

    internal fun filters(target: String): List<DiscoveryFilter> {
        runtime.checkAvailable()
        return provider.filters(target).map {
            if (it is DiscoveryFilter.Choice) it.copy(options = it.options.toMap()) else it
        }
    }

    internal suspend fun page(request: DiscoveryRequest): Result<SourceDiscoveryPage, DiscoveryError> = runtime.execute {
        if (!hasFeed && !hasCategories) return@execute Err(DiscoveryError.Unsupported)
        provider.page(request.copy(filters = request.filters.toMap())).map {
            SourceDiscoveryPage(it.books.map(::bind), it.nextCursor)
        }
    }

    private fun target(id: String) = SourceDiscoveryTarget(runtime.id, id)
    private fun bind(book: DiscoveryBook) = SourceDiscoveryBook(
        SourceBookId(runtime.id, book.remoteId), book.title, book.author, book.coverUrl,
    )
}

/** One instance per result page. Failed loads keep the cursor for retry; reset and load serialize. */
class DiscoverySession internal constructor(private val source: SourceDiscovery, private val target: String) {
    val filters = source.filters(target)
    private val mutex = Mutex()
    private var values: Map<String, String> = emptyMap()
    private var cursor: String? = null
    private var ended = false
    private var books: List<SourceDiscoveryBook> = emptyList()

    suspend fun reset(filters: Map<String, String> = emptyMap()) = mutex.withLock {
        values = filters.toMap()
        cursor = null
        ended = false
        books = emptyList()
    }

    suspend fun loadMore(): Result<SourceDiscoveryPage, DiscoveryError> = mutex.withLock {
        if (ended) return@withLock Ok(SourceDiscoveryPage(books.toList(), null))
        source.page(DiscoveryRequest(target, cursor, values)).map { page ->
            books = (books + page.books).distinctBy { it.id }
            cursor = page.nextCursor
            ended = cursor == null
            SourceDiscoveryPage(books.toList(), cursor)
        }
    }
}
