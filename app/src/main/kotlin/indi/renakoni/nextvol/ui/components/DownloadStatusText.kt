package indi.renakoni.nextvol.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskStatus

@Composable
fun downloadStatusLabel(status: BookDownloadStatus): String = stringResource(when (status.task.status) {
    DownloadTaskStatus.Queued -> R.string.download_task_queued
    DownloadTaskStatus.WaitingRetry -> R.string.download_task_waiting_retry
    DownloadTaskStatus.Running -> R.string.book_download_updating
    DownloadTaskStatus.Interrupted -> R.string.download_task_interrupted
    DownloadTaskStatus.Cancelled -> R.string.download_task_cancelled
    DownloadTaskStatus.Failed -> R.string.book_download_failed
    else -> when (status.content.phase) {
        BookDownloadPhase.None -> R.string.cached_false
        BookDownloadPhase.Partial -> R.string.book_download_partial
        BookDownloadPhase.Complete -> R.string.cached
        BookDownloadPhase.Updating -> R.string.book_download_updating
        BookDownloadPhase.Failed -> R.string.book_download_failed
        BookDownloadPhase.Outdated -> R.string.book_download_outdated
    }
})

@Composable
fun downloadStatusText(status: BookDownloadStatus): String {
    val parts = mutableListOf(downloadStatusLabel(status),
        stringResource(R.string.download_task_content, status.content.savedChapters, status.content.totalChapters))
    if (status.task.status == DownloadTaskStatus.WaitingRetry) {
        parts += stringResource(R.string.download_task_retry_time, status.task.retryCount,
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(status.task.nextAttemptAt)))
    }
    if (status.task.active || status.task.canResume) {
        parts += stringResource(when (status.task.stage) {
            DownloadStage.Unknown -> R.string.download_stage_unknown
            DownloadStage.Details -> R.string.download_stage_details
            DownloadStage.Directory -> R.string.download_stage_directory
            DownloadStage.Body -> R.string.download_stage_body
            DownloadStage.Image -> R.string.download_stage_image
            DownloadStage.Cover -> R.string.download_stage_cover
            DownloadStage.Storage -> R.string.download_stage_storage
        })
        status.task.chapterIndex?.let { parts += stringResource(R.string.download_task_chapter, it) }
    }
    status.task.failure?.let { failure -> parts += stringResource(when (failure) {
        DownloadFailure.Network -> R.string.download_error_network
        DownloadFailure.RateLimited -> R.string.download_error_rate_limited
        DownloadFailure.RetryExhausted -> R.string.download_error_retry_exhausted
        DownloadFailure.Authentication -> R.string.download_error_authentication
        DownloadFailure.Verification -> R.string.download_error_verification
        DownloadFailure.SourceUnavailable -> R.string.download_error_source
        DownloadFailure.SourceRequest -> R.string.download_error_request
        DownloadFailure.Storage -> R.string.download_error_storage
        DownloadFailure.SystemRestricted -> R.string.download_error_system_restricted
        DownloadFailure.SystemInterrupted -> R.string.download_error_system_interrupted
    }) }
    return parts.joinToString(" · ")
}
