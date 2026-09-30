package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.ui.book.reader.DirectorySearchMatch
import io.nightfish.lightnovelreader.api.book.Volume

internal const val DIRECTORY_PAGE_SIZE = 100

internal fun directoryPageCount(chapterCount: Int): Int =
    ((chapterCount + DIRECTORY_PAGE_SIZE - 1) / DIRECTORY_PAGE_SIZE).coerceAtLeast(1)

internal fun directoryPageRange(chapterCount: Int, page: Int): IntRange {
    val start = page.coerceIn(0, directoryPageCount(chapterCount) - 1) * DIRECTORY_PAGE_SIZE
    return start until minOf(start + DIRECTORY_PAGE_SIZE, chapterCount)
}

internal fun directoryChapters(volumes: List<Volume>): List<DirectorySearchMatch> =
    volumes.flatMap { volume ->
        volume.chapters.map { DirectorySearchMatch(volume.volumeId, volume.volumeTitle, it) }
    }

internal fun directoryPageChapters(allChapters: List<DirectorySearchMatch>, page: Int, descending: Boolean): List<DirectorySearchMatch> {
    val range = directoryPageRange(allChapters.size, page)
    val chapters = if (range.isEmpty()) emptyList() else allChapters.subList(range.first, range.last + 1)
    return if (descending) chapters.asReversed() else chapters
}
