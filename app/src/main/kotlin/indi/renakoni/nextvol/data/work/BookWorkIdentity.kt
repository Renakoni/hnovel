package indi.renakoni.nextvol.data.work

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.bookRequestFailureReason
import io.nightfish.lightnovelreader.api.error.WebRequestError

/** Workers never interpret a bare ID using the UI selection or a legacy default. */
internal fun Data.sourceBook(): SourceBookId? = getString("bookId")?.let {
    runCatching { SourceBookId.fromStorageKey(it) }.getOrNull()
}

internal fun bookWorkFailure(reason: String, book: SourceBookId? = null) =
    ListenableWorker.Result.failure(workDataOf("reason" to reason, "bookId" to book?.storageKey))

internal fun bookWorkFailureReason(error: WebRequestError?): String = bookRequestFailureReason(error)
