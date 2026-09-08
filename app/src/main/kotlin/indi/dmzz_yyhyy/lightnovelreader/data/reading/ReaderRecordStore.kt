package indi.dmzz_yyhyy.lightnovelreader.data.reading

import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import io.nightfish.lightnovelreader.api.book.UserReadingData

/** Persistence operations used by reader recording, without chapter loading or cache scheduling. */
internal interface ReaderRecordStore {
    suspend fun updateRecentBooks(update: (List<String>) -> List<String>)
    suspend fun updateUserReadingData(bookId: String, update: (UserReadingData) -> UserReadingData)
    suspend fun getUserReadingData(bookId: String): UserReadingData
    suspend fun updateReadingStatistics(update: ReadingStatsUpdate)
    suspend fun markBookFinished(bookId: String)
    suspend fun accumulateBookReadTime(bookId: String, seconds: Int)
}
