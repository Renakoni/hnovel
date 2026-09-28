package indi.renakoni.nextvol.data.download

import androidx.work.WorkInfo
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.image.SourceImageRequestException
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import java.io.IOException

enum class DownloadTaskStatus { None, Queued, Running, Interrupted, Failed, Cancelled, Complete }
enum class DownloadStage { Unknown, Details, Directory, Body, Image, Cover, Storage }
enum class DownloadFailure { Network, Authentication, Verification, SourceUnavailable, SourceRequest, Storage }

data class DownloadTaskState(
    val status: DownloadTaskStatus = DownloadTaskStatus.None,
    val stage: DownloadStage = DownloadStage.Unknown,
    val chapterId: String = "",
    val failure: DownloadFailure? = null,
    val runAttemptCount: Int = 0,
    val chapterIndex: Int? = null,
) {
    val active get() = status == DownloadTaskStatus.Queued || status == DownloadTaskStatus.Running
    val canResume get() = status in setOf(DownloadTaskStatus.Interrupted, DownloadTaskStatus.Failed, DownloadTaskStatus.Cancelled)
}

data class BookDownloadStatus(
    val content: BookDownloadState = BookDownloadState(),
    val task: DownloadTaskState = DownloadTaskState(),
) {
    val displayPhase get() = when {
        task.active -> BookDownloadPhase.Updating
        task.status == DownloadTaskStatus.Failed -> BookDownloadPhase.Failed
        else -> content.phase
    }
}

/** Persist only an allow-listed category, never exception messages, headers or source URLs. */
internal fun downloadFailure(error: WebRequestError?, stage: DownloadStage): DownloadFailure {
    val image = error?.throwable as? SourceImageRequestException
    return when (image?.kind ?: error?.kind) {
        WebRequestErrorKind.AuthenticationRequired -> DownloadFailure.Authentication
        WebRequestErrorKind.VerificationRequired -> DownloadFailure.Verification
        WebRequestErrorKind.SourceUnavailable -> DownloadFailure.SourceUnavailable
        else -> if (stage == DownloadStage.Storage || error?.throwable is android.database.sqlite.SQLiteException) DownloadFailure.Storage
            else if (image?.networkFailure ?: (error?.throwable is IOException)) DownloadFailure.Network else DownloadFailure.SourceRequest
    }
}

/** A persisted running flag is not evidence of a live executor after process reconstruction. */
internal fun BookDownloadEntity.taskState(work: WorkInfo.State?): DownloadTaskState {
    val stored = DownloadTaskStatus.entries.firstOrNull { it.name == taskStatus } ?: DownloadTaskStatus.None
    val resolved = when {
        stored == DownloadTaskStatus.Cancelled -> stored
        work == WorkInfo.State.RUNNING -> DownloadTaskStatus.Running
        work == WorkInfo.State.ENQUEUED || work == WorkInfo.State.BLOCKED -> DownloadTaskStatus.Queued
        work == WorkInfo.State.CANCELLED -> DownloadTaskStatus.Cancelled
        work == WorkInfo.State.FAILED -> DownloadTaskStatus.Failed
        stored == DownloadTaskStatus.Running || stored == DownloadTaskStatus.Queued -> DownloadTaskStatus.Interrupted
        stored == DownloadTaskStatus.None && phase == "updating" -> DownloadTaskStatus.Interrupted
        stored == DownloadTaskStatus.None && phase == "failed" -> DownloadTaskStatus.Failed
        else -> stored
    }
    return DownloadTaskState(resolved, DownloadStage.entries.firstOrNull { it.name == taskStage } ?: DownloadStage.Unknown,
        taskChapter, DownloadFailure.entries.firstOrNull { it.name == taskError }, taskRunAttempt)
}
