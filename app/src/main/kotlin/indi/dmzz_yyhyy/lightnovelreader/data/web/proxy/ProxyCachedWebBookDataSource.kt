package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onOk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import io.nightfish.lightnovelreader.api.util.Cache

class ProxyCachedWebBookDataSource(
    override val proxiedWebBookDataSource: ProxyWebBookDataSource,
    private val requestCache: Cache? = proxiedWebBookDataSource.origin.cache,
) : ProxyWebBookDataSource {
    private enum class RequestType { Information, Volumes, Chapter }

    private data class RequestKey(
        val type: RequestType,
        val bookId: String,
        val chapterId: String? = null,
    )

    private inline fun <reified T : Any> getOrCache(
        key: RequestKey,
        refresh: Boolean,
        block: () -> Result<T, WebRequestError>
    ): Result<T, WebRequestError> {
        val cache = requestCache ?: return block()
        val value = if (refresh) null else synchronized(cache) { cache.getCache<T>(key) }
        value ?: return block.invoke()
            .onOk { value -> synchronized(cache) { cache.cache(key, value) } }
        return Ok(value)
    }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) = getBookInformation(id, priority, false)

    suspend fun getBookInformation(id: String, priority: WebDataSourcePriority, refresh: Boolean) = getOrCache(RequestKey(RequestType.Information, id), refresh) {
        proxiedWebBookDataSource.getBookInformation(id, priority)
    }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) = getBookVolumes(id, priority, false)

    suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority, refresh: Boolean) = getOrCache(RequestKey(RequestType.Volumes, id), refresh) {
        proxiedWebBookDataSource.getBookVolumes(id, priority)
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) = getChapterContent(chapterId, bookId, priority, false)

    suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority, refresh: Boolean) = getOrCache(RequestKey(RequestType.Chapter, bookId, chapterId), refresh) {
        proxiedWebBookDataSource.getChapterContent(chapterId, bookId, priority)
    }
}
