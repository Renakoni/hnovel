package indi.renakoni.nextvol.data.book

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.SourceContentException
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError

/** Only reading UI may use these volumes; downloads and update checks still receive an error. */
internal class PartialBookVolumesException(val volumes: BookVolumes, val failure: SourceContentException) :
    SourceContentException(failure.code, failure.field, failure.denial, failure.dependency,
        failure.verification, failure.diagnostic, failure.httpStatus, failure.retry) {
    init { initCause(failure) }
}

internal fun Result<BookVolumes, WebRequestError>.availableVolumes(): BookVolumes? =
    get() ?: (getError()?.throwable as? PartialBookVolumesException)?.volumes

internal fun WebRequestError.mapAvailableVolumes(transform: (BookVolumes) -> BookVolumes): WebRequestError {
    val partial = throwable as? PartialBookVolumesException ?: return this
    return copy(throwable = PartialBookVolumesException(transform(partial.volumes), partial.failure))
}
