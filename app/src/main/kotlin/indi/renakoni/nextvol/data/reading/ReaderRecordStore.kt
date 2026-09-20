package indi.renakoni.nextvol.data.reading

import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import io.nightfish.lightnovelreader.api.book.UserReadingData

/** Persistence operations used by reader recording, without chapter loading or cache scheduling. */
internal interface ReaderRecordStore {
    fun progressRevision(): Long
    suspend fun updateChapterProgress(
        bookId: String, chapterId: String, revision: Long,
        update: (UserReadingData) -> UserReadingData,
    ): Boolean
    suspend fun updateRecentBooks(update: (List<String>) -> List<String>)
    suspend fun updateUserReadingData(bookId: String, update: (UserReadingData) -> UserReadingData)
    suspend fun getUserReadingData(bookId: String): UserReadingData
    suspend fun updateReadingStatistics(update: ReadingStatsUpdate)
    suspend fun markBookFinished(bookId: String)
    suspend fun accumulateBookReadTime(bookId: String, seconds: Int)
}
