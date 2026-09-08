package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.net.Uri
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FlipPaginationInput(
    val chapterId: String,
    val content: List<AbstractContentComponent<*>>,
    val contentSize: IntSize,
    val horizontalPadding: Int,
    val verticalPadding: Int,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val readerStyle: ReaderStyle,
    val fontFamilyUri: Uri,
    val textLocaleList: LocaleList,
)

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
    private var latestInput: Any? = null

    /** Invalidates a request when Compose has observed a new pagination input. */
    fun syncInput(input: Any) {
        if (latestInput == input) return
        latestInput = input
        requestId++
        paginationJob?.cancel()
        paginationJob = null
    }

    fun submit(
        input: Any,
        components: List<AbstractContentComponent<*>>,
        height: Int,
        width: Int,
        onComplete: (List<AbstractContentComponent<*>>) -> Unit,
    ) {
        syncInput(input)
        val request = ++requestId
        paginationJob?.cancel()
        paginationJob = scope.launch {
            val result = withContext(ioDispatcher) {
                paginate(components, height, width)
            }
            if (request == requestId && latestInput == input) onComplete(result)
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
