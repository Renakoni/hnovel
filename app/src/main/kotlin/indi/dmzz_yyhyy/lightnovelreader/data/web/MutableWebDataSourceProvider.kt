package indi.dmzz_yyhyy.lightnovelreader.data.web

import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority

class MutableWebDataSourceProvider : WebBookDataSourceProvider {
    @Volatile private var _value: ProxyWebBookDataSource = placeholder(EmptyWebDataSource)
    override val value: ProxyWebBookDataSource
        get() = _value

    override fun isWebDataSourceFounded(): Boolean =
        _value.origin !== EmptyWebDataSource && _value.origin !is NotFoundWebDataSource

    fun bind(runtime: SourceRuntime) {
        _value = runtime.legacyProxy
    }

    fun unavailable(source: NotFoundWebDataSource) {
        _value = placeholder(source)
    }

    private fun placeholder(source: WebBookDataSource) = object : ProxyWebBookDataSource {
        override val origin = source
        override val proxiedWebBookDataSource: ProxyWebBookDataSource get() = this
        override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) = source.getBookInformation(id)
        override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) = source.getBookVolumes(id)
        override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) =
            source.getChapterContent(chapterId, bookId)
    }
}
