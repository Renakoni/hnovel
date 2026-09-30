package indi.renakoni.nextvol.data.web.proxy

import indi.renakoni.nextvol.coroutine.PriorityDispatcher
import hnovel.execution.SourceWorkRequest
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class ProxyPriorityWebBookDataSource(
    override val origin: WebBookDataSource
) : ProxyWebBookDataSource {
    override val proxiedWebBookDataSource: ProxyWebBookDataSource = this
    private val dispatcher: PriorityDispatcher = PriorityDispatcher(origin.permits)

    suspend fun close() = dispatcher.close()

    private suspend fun <T> prioritized(priority: WebDataSourcePriority, block: suspend () -> T): T {
        val effective = maxOf(priority.priority, currentCoroutineContext()[SourceWorkRequest]?.priority ?: priority.priority)
        return withContext(dispatcher + PriorityDispatcher.Priority(effective) + SourceWorkRequest(effective)) { block() }
    }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) = prioritized(priority) {
        origin.getBookInformation(id)
    }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) = prioritized(priority) {
        origin.getBookVolumes(id)
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) = prioritized(priority) {
        origin.getChapterContent(chapterId, bookId)
    }
}
