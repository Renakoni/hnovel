package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import com.santimattius.resilient.composition.asResilientScope
import com.santimattius.resilient.coalescing.CoalesceConfig
import com.santimattius.resilient.coalescing.DefaultCoalescingPolicy
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ProxyCoalescingWebBookDataSource(
    override val proxiedWebBookDataSource: ProxyWebBookDataSource,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProxyWebBookDataSource, AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher).asResilientScope()
    @Serializable private enum class RequestType { Information, Volumes, Chapter }
    @Serializable
    private data class RequestKey(val type: RequestType, val bookId: String, val priority: Int, val chapterId: String? = null)
    private class RequestContext(val request: RequestKey) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestContext>
    }
    // The policy owns the in-flight map. Building a new policy per call defeats
    // coalescing even when all calls use the same ResilientScope and key.
    private val coalescing = DefaultCoalescingPolicy(CoalesceConfig().apply {
        keyProvider = { Json.encodeToString(requireNotNull(currentCoroutineContext()[RequestContext]).request) }
    }, scope)

    override fun close() = lifetime.cancel()

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
    }

    private suspend fun <T> coalesce(key: RequestKey, block: suspend () -> T): T =
        withContext(RequestContext(key)) { coalescing.execute(block) }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) = coalesce(RequestKey(RequestType.Information, id, priority.priority)) {
        proxiedWebBookDataSource.getBookInformation(id, priority)
    }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) = coalesce(RequestKey(RequestType.Volumes, id, priority.priority)) {
        proxiedWebBookDataSource.getBookVolumes(id, priority)
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) = coalesce(RequestKey(RequestType.Chapter, bookId, priority.priority, chapterId)) {
        proxiedWebBookDataSource.getChapterContent(chapterId, bookId, priority)
    }
}
