package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume

internal fun SourceBookId.bind(info: BookInformation): BookInformation {
    require(info.id == remoteId) { "Book response has a different remote ID" }
    return info.copy(id = storageKey)
}

internal fun SourceBookId.bind(volumes: BookVolumes): BookVolumes {
    require(volumes.bookId == remoteId) { "Directory response has a different book ID" }
    return volumes.copy(bookId = storageKey, volumes = volumes.volumes.map { volume ->
        volume.copy(volumeId = BookIdentity.volumeKey(this, volume.volumeId), chapters = volume.chapters.map {
            it.copy(id = SourceChapterId(this, it.id).storageKey)
        })
    })
}

internal fun SourceChapterId.bind(content: ChapterContent): ChapterContent {
    require(content.id == remoteId) { "Chapter response has a different remote ID" }
    return content.copy(id = storageKey,
        prevChapter = content.prevChapter?.takeIf(String::isNotEmpty)?.let { SourceChapterId(book, it).storageKey },
        nextChapter = content.nextChapter?.takeIf(String::isNotEmpty)?.let { SourceChapterId(book, it).storageKey })
}

internal fun SourceBookId.bindReadingData(data: UserReadingData): UserReadingData {
    require(BookIdentity.book(data.id) == this) { "Reading update cannot change its book" }
    return data.copy(id = storageKey,
        lastReadChapterId = data.lastReadChapterId?.takeIf(String::isNotEmpty)?.let { BookIdentity.chapter(it, this).storageKey },
        currentChapterReadingProgressMap = data.currentChapterReadingProgressMap.mapKeys { BookIdentity.chapter(it.key, this).storageKey },
        maxChapterReadingProgressMap = data.maxChapterReadingProgressMap.mapKeys { BookIdentity.chapter(it.key, this).storageKey })
}

/** The legacy source volume-cover callback consumes its own remote IDs, never host keys. */
internal fun SourceBookId.remoteVolume(volume: Volume): Volume = volume.copy(
    volumeId = BookIdentity.volumeRemoteId(volume.volumeId, this),
    chapters = volume.chapters.map { it.copy(id = BookIdentity.chapter(it.id, this).remoteId) },
)

internal fun SourceBookId.remoteContent(content: ChapterContent): ChapterContent = content.copy(
    id = BookIdentity.chapter(content.id, this).remoteId,
    prevChapter = content.prevChapter?.takeIf(String::isNotEmpty)?.let { BookIdentity.chapter(it, this).remoteId },
    nextChapter = content.nextChapter?.takeIf(String::isNotEmpty)?.let { BookIdentity.chapter(it, this).remoteId },
)
