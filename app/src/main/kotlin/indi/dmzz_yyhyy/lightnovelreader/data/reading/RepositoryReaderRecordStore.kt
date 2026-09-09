package indi.dmzz_yyhyy.lightnovelreader.data.reading

import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath

internal class RepositoryReaderRecordStore(
    private val readingData: BookReadingDataAccess,
    private val statsRepository: StatsRepository,
    userDataRepository: UserDataRepository,
) : ReaderRecordStore {
    private val readingBooks = userDataRepository.stringListUserData(UserDataPath.ReadingBooks.path)

    override suspend fun updateRecentBooks(update: (List<String>) -> List<String>) =
        readingBooks.update { update(it).map(BookIdentity::bookKey).distinct() }

    override suspend fun updateUserReadingData(
        bookId: String,
        update: (UserReadingData) -> UserReadingData,
    ) = readingData.updateUserReadingData(bookId, update)

    override suspend fun getUserReadingData(bookId: String): UserReadingData =
        readingData.getUserReadingData(bookId)

    override suspend fun updateReadingStatistics(update: ReadingStatsUpdate) =
        statsRepository.updateReadingStatistics(update)

    override suspend fun markBookFinished(bookId: String) = statsRepository.markBookFinished(bookId)

    override suspend fun accumulateBookReadTime(bookId: String, seconds: Int) =
        statsRepository.accumulateBookReadTime(bookId, seconds)
}
