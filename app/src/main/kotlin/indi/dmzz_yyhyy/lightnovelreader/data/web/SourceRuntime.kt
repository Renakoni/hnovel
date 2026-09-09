package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.content.Context
import android.util.Log
import androidx.navigation.NavController
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyCachedWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyCoalescingWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyPriorityWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One registration generation. New callers never need a global provider or plugin manager. */
class SourceRuntime internal constructor(
    val metadata: SourceMetadata,
    private val source: WebBookDataSource,
    private val lifetime: CoroutineScope,
    private val cleanupScope: CoroutineScope,
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
        val request = lifetime.async { block() }
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
    }

    internal fun ownedScope(parent: CoroutineScope): CoroutineScope {
        checkAvailable()
        val child = Job(parent.coroutineContext[Job])
        val handle = requireNotNull(lifetime.coroutineContext[Job]).invokeOnCompletion { child.cancel() }
        child.invokeOnCompletion { handle.dispose() }
        return CoroutineScope(parent.coroutineContext + child)
    }

    fun bookTagPage(tag: String): String? {
        checkAvailable()
        return source.bookTagPage(tag)
    }

    suspend fun volumeCover(bookId: String, volume: Volume,
        chapters: MutableMap<String, ChapterContent>, context: Context) =
        execute { source.getCoverUriInVolume(bookId, volume, chapters, context) }

    internal val explorePages get() = run { checkAvailable(); legacyExplore }

    val discovery: SourceDiscovery? by lazy { source.discoveryProvider?.let { SourceDiscovery(this, it) } }

    fun imageHeaders(): Map<String, String> {
        checkAvailable()
        return source.imageHeader.toMap()
    }

    suspend fun getBookInformation(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default) =
        execute { cached.getBookInformation(bookId, priority) }

    suspend fun getBookVolumes(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default) =
        execute { cached.getBookVolumes(bookId, priority) }

    suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default) =
        execute { cached.getChapterContent(chapterId, bookId, priority) }

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

    private val legacyExplore by lazy { guardExploreProvider(this, source.explorePageProvider) }

    /** Temporary synchronous API facade. onLoad cannot restart a source-owned poller. */
    private val legacySource = object : WebBookDataSource by source {
        // Data consumers must use the source-binding facade above, not the legacy raw API.
        override val discoveryProvider get() = null
        override fun onLoad() = checkAvailable()
        override val cache get() = null
        override val searchProvider get() = search
        override val imageHeader get() = imageHeaders()
        override fun bookTagPage(tag: String) = this@SourceRuntime.bookTagPage(tag)
        override val explorePageProvider get() = run { checkAvailable(); legacyExplore }
        override val offLine get() = !isAvailable || source.offLine
        override val isOffLineFlow get() = source.isOffLineFlow.also { checkAvailable() }
        override suspend fun isOffLine() = execute { source.isOffLine() }
        override suspend fun getBookInformation(id: String) = this@SourceRuntime.getBookInformation(id)
        override suspend fun getBookVolumes(id: String) = this@SourceRuntime.getBookVolumes(id)
        override suspend fun getChapterContent(chapterId: String, bookId: String) =
            this@SourceRuntime.getChapterContent(chapterId, bookId)
        override fun progressBookTagClick(tag: String, navController: NavController) {
            checkAvailable()
            source.progressBookTagClick(tag, navController)
        }
        override suspend fun getCoverUriInVolume(bookId: String, volume: Volume,
            volumeChapterContentMap: MutableMap<String, ChapterContent>, context: Context) =
            execute { source.getCoverUriInVolume(bookId, volume, volumeChapterContentMap, context) }
    }

    internal val legacyProxy = object : ProxyWebBookDataSource {
        override val origin: WebBookDataSource get() = legacySource
        override val proxiedWebBookDataSource: ProxyWebBookDataSource get() = this
        override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) =
            this@SourceRuntime.getBookInformation(id, priority)
        override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) =
            this@SourceRuntime.getBookVolumes(id, priority)
        override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) =
            this@SourceRuntime.getChapterContent(chapterId, bookId, priority)
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
