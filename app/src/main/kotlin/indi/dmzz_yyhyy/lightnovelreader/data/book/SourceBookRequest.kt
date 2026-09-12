package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceUnavailableException
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Missing sources retain their local namespace; requests never fall back to another source. */
internal suspend fun <T> WebSourceRegistry.request(
    book: SourceBookId,
    block: suspend (SourceRuntime) -> Result<T, WebRequestError>,
): Result<T, WebRequestError> = when (val resolution = resolve(book.sourceId)) {
    is SourceResolution.Ready -> try {
        block(resolution.runtime)
    } catch (failure: Exception) {
        // Caller lifecycle cancellation always wins, even if this registration retired too.
        currentCoroutineContext().ensureActive()
        // Only a retired runtime's cancellation/unavailability becomes SourceUnavailable.
        // IOException and other provider failures must propagate even after retirement:
        // relabeling them would hide the actual fault and give callers the wrong recovery path.
        if (resolution.runtime.isAvailable ||
            (failure !is CancellationException && failure !is SourceUnavailableException)) throw failure
        Err(WebRequestError("Data source unavailable", "Source was removed or replaced (${book.sourceId})", failure, WebRequestErrorKind.SourceUnavailable))
    }
    is SourceResolution.Missing -> Err(WebRequestError("Data source not found", "Source is not registered (${book.sourceId})", kind = WebRequestErrorKind.SourceUnavailable))
    is SourceResolution.Unavailable -> Err(WebRequestError("Data source unavailable", "Source initialization failed (${book.sourceId})", resolution.cause, WebRequestErrorKind.SourceUnavailable))
}
