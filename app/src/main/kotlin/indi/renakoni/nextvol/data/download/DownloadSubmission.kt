package indi.renakoni.nextvol.data.download

import java.util.UUID

/** Submission acknowledgement, not a claim that the source has returned any content. */
sealed interface DownloadSubmission {
    data class Accepted(
        val workId: UUID,
        val task: DownloadTaskState,
        val existing: Boolean = false,
        val selectionMatches: Boolean = true,
    ) : DownloadSubmission

    data class Rejected(val failure: DownloadFailure) : DownloadSubmission
}
