package indi.dmzz_yyhyy.lightnovelreader.data.work

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind

/** Workers never interpret a bare ID using the UI selection or a legacy default. */
internal fun Data.sourceBook(): SourceBookId? = getString("bookId")?.let {
    runCatching { SourceBookId.fromStorageKey(it) }.getOrNull()
}

internal fun bookWorkFailure(reason: String, book: SourceBookId? = null) =
    ListenableWorker.Result.failure(workDataOf("reason" to reason, "bookId" to book?.storageKey))

internal fun bookWorkFailureReason(error: WebRequestError?): String = when (error?.kind) {
    WebRequestErrorKind.AuthenticationRequired -> "authentication_required"
    WebRequestErrorKind.SourceUnavailable -> "source_unavailable"
    else -> "source_request_failed"
}
