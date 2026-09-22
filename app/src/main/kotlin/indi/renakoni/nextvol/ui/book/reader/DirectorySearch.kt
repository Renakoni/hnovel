package indi.renakoni.nextvol.ui.book.reader

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DirectorySearchMatch(
    val volumeId: String,
    val volumeTitle: String,
    val chapter: ChapterInformation,
) {
    val key: String get() = "chapter:${volumeId.length}:$volumeId:${chapter.id}"
}

internal suspend fun searchDirectory(book: BookVolumes, query: String): List<DirectorySearchMatch> {
    val titleQuery = query.trim()
    if (titleQuery.isEmpty()) return emptyList()
    val context = currentCoroutineContext()
    return buildList {
        for (volume in book.volumes) for (chapter in volume.chapters) {
            context.ensureActive()
            if (chapter.title.contains(titleQuery, ignoreCase = true)) {
                add(DirectorySearchMatch(volume.volumeId, volume.volumeTitle, chapter))
            }
        }
    }
}
