package indi.dmzz_yyhyy.lightnovelreader.data.statistics

import android.app.Application
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookRecordDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DailyCountDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookRecordEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DailyCountEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Statistics summary precision contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class StatsRepositoryCharacterizationTest {
    private val records = mutableMapOf<Pair<String, LocalDate>, BookRecordEntity>()
    private val dailyCounts = mutableMapOf<LocalDate, DailyCountEntity>()
    private val recordDao = mockk<BookRecordDao> {
        coEvery { getBookRecordByIdAndDate(any(), any()) } answers { records[firstArg<String>() to secondArg<LocalDate>()] }
        coEvery { insertBookRecord(any()) } answers {
            val record = firstArg<BookRecordEntity>()
            records[record.bookId to record.date] = record
        }
        coEvery { getAllBookRecords() } answers { records.values.toList() }
    }
    private val dailyDao = mockk<DailyCountDao> {
        coEvery { getByDate(any()) } answers { dailyCounts[firstArg<LocalDate>()] }
        coEvery { insert(any()) } answers {
            val record = firstArg<DailyCountEntity>()
            dailyCounts[record.date] = record
        }
        coEvery { getAll() } answers { dailyCounts.values.toList() }
    }
    private val repository = StatsRepository(recordDao, dailyDao, mockk())

    @Test
    fun recordingAnEntryDiscardsSecondsAlreadyBufferedForThatBook() = runTest {
        repository.accumulateBookReadTime("book", 10)
        repository.updateReadingStatistics(ReadingStatsUpdate(bookId = "book", readEventDelta = 1))
        repository.accumulateBookReadTime("book", -1)

        assertEquals(1, records.values.sumOf { it.reads })
        assertEquals(0, records.values.sumOf { it.seconds })
    }

    @Test
    fun flushingOneBookDiscardsAnotherBooksUnflushedSeconds() = runTest {
        repository.accumulateBookReadTime("first", 10)
        repository.accumulateBookReadTime("second", 20)
        repository.accumulateBookReadTime("second", -1)
        repository.accumulateBookReadTime("first", -1)

        assertEquals(20, records.values.sumOf { it.seconds })
        assertFalse(records.values.any { it.bookId == "first" })
    }

    @Test
    fun twoThirtySecondSettlementsProduceOneSummaryMinuteFromBookSeconds() = runTest {
        repeat(2) {
            repository.accumulateBookReadTime("book", 30)
            repository.accumulateBookReadTime("book", -1)
        }

        assertEquals(60, records.values.sumOf { it.seconds })
        assertEquals(1, repository.getTotalReadingSummary().totalMinutes)
    }
}
