package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.ui.book.reader.DirectorySearchMatch
import io.nightfish.lightnovelreader.api.book.Volume

internal const val DIRECTORY_PAGE_SIZE = 100

internal fun directoryPageCount(chapterCount: Int): Int =
    ((chapterCount + DIRECTORY_PAGE_SIZE - 1) / DIRECTORY_PAGE_SIZE).coerceAtLeast(1)

internal fun directoryPageRange(chapterCount: Int, page: Int, descending: Boolean = false): IntProgression {
    val start = page.coerceIn(0, directoryPageCount(chapterCount) - 1) * DIRECTORY_PAGE_SIZE
    return if (descending) (chapterCount - 1 - start) downTo maxOf(0, chapterCount - start - DIRECTORY_PAGE_SIZE)
        else start until minOf(start + DIRECTORY_PAGE_SIZE, chapterCount)
}

internal fun directoryPageForChapter(chapterCount: Int, chapterIndex: Int, descending: Boolean): Int {
    if (chapterIndex !in 0 until chapterCount) return 0
    val position = if (descending) chapterCount - 1 - chapterIndex else chapterIndex
    return position / DIRECTORY_PAGE_SIZE
}

internal fun directoryChapters(volumes: List<Volume>): List<DirectorySearchMatch> =
    volumes.flatMap { volume ->
        volume.chapters.map { DirectorySearchMatch(volume.volumeId, volume.volumeTitle, it) }
    }

internal fun directoryPageChapters(allChapters: List<DirectorySearchMatch>, page: Int, descending: Boolean): List<DirectorySearchMatch> =
    directoryPageRange(allChapters.size, page, descending).map { allChapters[it] }
