package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one cancellable pagination request and publishes only its newest result. */
internal class FlipPaginationCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val paginate: suspend (
        List<AbstractContentComponent<*>>,
        Int,
        Int,
    ) -> List<AbstractContentComponent<*>> = ::paginateComponents,
) {
    private var paginationJob: Job? = null
    private var requestId = 0L

    fun submit(
        components: List<AbstractContentComponent<*>>,
        height: Int,
        width: Int,
        onComplete: (List<AbstractContentComponent<*>>) -> Unit,
    ) {
        val request = ++requestId
        paginationJob?.cancel()
        paginationJob = scope.launch {
            val result = withContext(ioDispatcher) {
                paginate(components, height, width)
            }
            if (request == requestId) onComplete(result)
        }
    }

    fun close() {
        cancelPending()
    }

    /** Invalidates an in-flight request while keeping the coordinator reusable. */
    fun cancelPending() {
        requestId++
        paginationJob?.cancel()
        paginationJob = null
    }
}
