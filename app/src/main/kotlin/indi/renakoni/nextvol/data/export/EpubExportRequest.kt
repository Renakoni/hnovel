package indi.renakoni.nextvol.data.export

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import java.util.UUID
import kotlinx.coroutines.CancellationException

// Names are persisted in WorkManager requests; do not rename these values.
enum class ExportType {
    BOOK,
    VOLUMES
}

data class EpubExportRequest(
    val id: UUID,
    val book: SourceBookId,
    val type: ExportType,
    val selectedVolumeIds: Set<String> = emptySet(),
    val includeImages: Boolean = true,
    val downloadGeneration: Long = 0,
) {
    init {
        if (type == ExportType.VOLUMES) selectedVolumeIds.forEach { BookIdentity.volumeRemoteId(it, book) }
    }
}

data class EpubExportProgress(
    val percent: Int,
    val phase: Phase,
    val completed: Int,
    val total: Int,
    val volume: String,
    val chapter: String,
) {
    enum class Phase { CHAPTERS, IMAGES }
}

sealed interface EpubExportResult {
    data class Success(
        val id: UUID,
        val book: SourceBookId,
        val completedVolumes: Int,
        val totalVolumes: Int,
    ) : EpubExportResult

    data class Failure(
        val book: SourceBookId,
        val reason: String,
        val stage: String = "preparing",
        val volume: String = "",
        val chapter: String = "",
        val completedVolumes: Int = 0,
        val totalVolumes: Int = 1,
    ) : EpubExportResult
}

/** Cancellation still propagates, with the location needed by the platform's failure notification. */
class EpubExportCancelled(val failure: EpubExportResult.Failure, cause: CancellationException) :
    CancellationException(cause.message) {
    init { initCause(cause) }
}
