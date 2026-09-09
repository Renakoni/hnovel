package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.error.WebRequestError

/** Missing sources retain their local namespace; requests never fall back to another source. */
internal suspend fun <T> WebSourceRegistry.request(
    book: SourceBookId,
    block: suspend (SourceRuntime) -> Result<T, WebRequestError>,
): Result<T, WebRequestError> = when (val resolution = resolve(book.sourceId)) {
    is SourceResolution.Ready -> block(resolution.runtime)
    is SourceResolution.Missing -> Err(WebRequestError("Data source not found", "Source is not registered (${book.sourceId})"))
    is SourceResolution.Unavailable -> Err(WebRequestError("Data source unavailable", "Source initialization failed (${book.sourceId})", resolution.cause))
}
