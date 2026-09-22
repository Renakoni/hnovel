package indi.renakoni.nextvol.data.web

import io.nightfish.lightnovelreader.api.image.SourceImageProvider

import android.content.Context
import android.util.Log
import indi.renakoni.nextvol.data.web.proxy.ProxyCachedWebBookDataSource
import indi.renakoni.nextvol.data.web.proxy.ProxyCoalescingWebBookDataSource
import indi.renakoni.nextvol.data.web.proxy.ProxyPriorityWebBookDataSource
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One registration generation. New callers never need a global provider or plugin manager. */
class SourceRuntime internal constructor(
    val metadata: SourceMetadata,
    private val source: WebBookDataSource,
    private val lifetime: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    internal val discoveryResolved: (Boolean) -> Unit = {},
) {
    val id get() = metadata.id
    val isAvailable get() = lifetime.isActive
    private val responseCache = source.cache?.let { Cache(it.maxCountEachType, it.timeout) }
    private val priority = ProxyPriorityWebBookDataSource(source)
    private val coalescing = ProxyCoalescingWebBookDataSource(priority)
    // A source's Cache is configuration, not shared runtime storage. Even adapters
    // accidentally returning one Cache instance cannot share host response entries.
    private val cached = ProxyCachedWebBookDataSource(coalescing, responseCache)

    internal fun checkAvailable() {
        if (!isAvailable) throw SourceUnavailableException(id)
    }

    internal suspend fun <T> execute(block: suspend () -> T): T {
        checkAvailable()
        val interaction = currentCoroutineContext()[ForegroundSourceRequest] ?: kotlin.coroutines.EmptyCoroutineContext
        val request = lifetime.async(interaction + SourceRequestOwner(id)) { block() }
        return try {
            request.await().also { checkAvailable() }
        } finally {
            request.cancel()
        }
    }

    internal fun <T> observe(block: () -> Flow<T>): Flow<T> = flow {
        coroutineScope {
            checkAvailable()
            val collection = requireNotNull(currentCoroutineContext()[Job])
            val handle = requireNotNull(lifetime.coroutineContext[Job]).invokeOnCompletion { collection.cancel() }
            try {
                block().collect { checkAvailable(); emit(it) }
            } finally {
                handle.dispose()
            }
        }
    }.flowOn(SourceRequestOwner(id))

    internal fun ownedScope(parent: CoroutineScope): CoroutineScope {
        checkAvailable()
        val child = Job(parent.coroutineContext[Job])
        val handle = requireNotNull(lifetime.coroutineContext[Job]).invokeOnCompletion { child.cancel() }
        child.invokeOnCompletion { handle.dispose() }
        return CoroutineScope(parent.coroutineContext + child + SourceRequestOwner(id))
    }

    fun bookTagPage(tag: String): String? {
        checkAvailable()
        return source.bookTagPage(tag)
    }

    suspend fun volumeCover(bookId: String, volume: Volume,
        chapters: MutableMap<String, ChapterContent>, context: Context) =
        execute { source.getCoverUriInVolume(bookId, volume, chapters, context) }

    val discovery: SourceDiscovery? by lazy { source.discoveryProvider?.let { SourceDiscovery(this, it) } }

    fun imageHeaders(): Map<String, String> {
        checkAvailable()
        return source.imageHeader.toMap()
    }

    val hasImageProvider get() = source is SourceImageProvider

    suspend fun imageBytes(bookId: String, url: String, cover: Boolean) = execute {
        (source as SourceImageProvider).getImage(bookId, url, cover)
    }

    suspend fun getBookInformation(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default, refresh: Boolean = false) =
        execute { cached.getBookInformation(bookId, priority, refresh) }

    suspend fun getBookVolumes(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default, refresh: Boolean = false) =
        execute { cached.getBookVolumes(bookId, priority, refresh) }

    suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default, refresh: Boolean = false) =
        execute { cached.getChapterContent(chapterId, bookId, priority, refresh) }

    val search: SearchProvider = object : SearchProvider {
        override val searchTypes get() = run { checkAvailable(); source.searchProvider.searchTypes.toList() }
        override fun search(searchType: SearchType, keyword: String) = observe {
            source.searchProvider.search(searchType, keyword)
        }
        override fun getSearchSuggestions(history: List<String>, keyword: String): List<String> {
            checkAvailable()
            return source.searchProvider.getSearchSuggestions(history, keyword)
        }
    }

    internal fun retire() {
        lifetime.cancel()
        coalescing.close()
        cleanupScope.launch {
            try {
                lifetime.coroutineContext[Job]?.join()
                // Coalesced work has its own scope. Await its cancellation handlers
                // before closing the client or shutting down their dispatcher.
                coalescing.closeAndJoin()
                (source as? AutoCloseable)?.close()
            } catch (failure: Exception) {
                Log.w("SourceRuntime", "Could not close source $id", failure)
            } finally {
                priority.close()
            }
        }
    }
}
