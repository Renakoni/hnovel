package indi.renakoni.nextvol.data.reading

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath

internal class RepositoryReaderRecordStore(
    private val readingData: BookReadingDataAccess,
    private val statsRepository: StatsRepository,
    userDataRepository: UserDataRepository,
) : ReaderRecordStore {
    override fun progressRevision(): Long = readingData.progressRevision()

    override suspend fun updateChapterProgress(
        bookId: String, chapterId: String, revision: Long,
        update: (UserReadingData) -> UserReadingData,
    ): Boolean = readingData.updateChapterProgress(bookId, chapterId, revision, update)

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
