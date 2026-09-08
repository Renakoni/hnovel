package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onOk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority

class ProxyCachedWebBookDataSource(
    override val proxiedWebBookDataSource: ProxyWebBookDataSource
) : ProxyWebBookDataSource {
    private enum class RequestType { Information, Volumes, Chapter }

    private data class RequestKey(
        val type: RequestType,
        val bookId: String,
        val chapterId: String? = null,
    )

    private inline fun <reified T : Any> getOrCache(
        key: RequestKey,
        block: () -> Result<T, WebRequestError>
    ): Result<T, WebRequestError> {
        val value = origin.cache?.getCache<T>(key) ?: return block.invoke()
            .onOk {
                origin.cache?.cache(key, it)
            }
        return Ok(value)
    }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) = getOrCache(RequestKey(RequestType.Information, id)) {
        proxiedWebBookDataSource.getBookInformation(id, priority)
    }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) = getOrCache(RequestKey(RequestType.Volumes, id)) {
        proxiedWebBookDataSource.getBookVolumes(id, priority)
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) = getOrCache(RequestKey(RequestType.Chapter, bookId, chapterId)) {
        proxiedWebBookDataSource.getChapterContent(chapterId, bookId, priority)
    }
}
