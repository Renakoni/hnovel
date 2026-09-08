package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.app.Application
import androidx.room.Room
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Exercises generated Room DAO methods against SQLite, including concurrent repository callers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class UserReadingDataTransactionTest {
    private lateinit var database: LightNovelReaderDatabase
    private lateinit var source: LocalBookDataSource

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), LightNovelReaderDatabase::class.java,
        ).build()
        source = LocalBookDataSource(
            database.bookInformationDao(), database.bookVolumesDao(),
            database.chapterContentDao(), database.userReadingDataDao(),
        )
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun readingTheOldValueAndTransformingItBelongToTheWriteTransaction() = runBlocking {
        source.updateUserReadingData("book") {
            assertTrue("The read/transform/write must share a Room transaction", database.inTransaction())
            it.copy(totalReadTime = 30)
        }
        assertEquals(30, source.getUserReadingData("book").totalReadTime)
    }

    @Test
    fun concurrentProgressAndTimeUpdatesKeepAllChanges() = runBlocking {
        source.updateUserReadingData("book") { it.copy(totalReadTime = 60) }
        coroutineScope {
            (0 until 20).flatMap { index ->
                listOf(
                    launch(Dispatchers.IO) {
                        source.updateUserReadingData("book") {
                            it.copyWithUpdatedChapterReadingProgress("chapter-$index", 0.8f)
                                .copy(lastReadChapterId = "chapter-$index", lastReadChapterTitle = "Chapter $index")
                        }
                    },
                    launch(Dispatchers.IO) {
                        source.updateUserReadingData("book") { it.copy(totalReadTime = it.totalReadTime + 30) }
                    },
                )
            }.joinAll()
        }
        val saved = source.getUserReadingData("book")
        assertEquals(660, saved.totalReadTime)
        val expected = (0 until 20).associate { "chapter-$it" to 0.8f }
        assertEquals(expected, saved.currentChapterReadingProgressMap)
        assertEquals(expected, saved.maxChapterReadingProgressMap)
        assertEquals("Chapter ${saved.lastReadChapterId!!.substringAfter('-')}", saved.lastReadChapterTitle)
    }

    @Test
    fun failedOrCancelledTransformationsLeaveDataAndTransactionQueueUsable() = runBlocking {
        source.updateUserReadingData("book") { it.copy(totalReadTime = 10) }
        for (failure in listOf(IllegalStateException("failed update"), CancellationException("cancelled update"))) {
            try {
                source.updateUserReadingData("book") { throw failure }
                throw AssertionError("Expected transformation failure")
            } catch (actual: Exception) {
                assertEquals(failure::class, actual::class)
                assertEquals(failure.message, actual.message)
            }
            assertEquals(10, source.getUserReadingData("book").totalReadTime)
        }
        source.updateUserReadingData("book") { it.copy(totalReadTime = it.totalReadTime + 20) }
        assertEquals(30, source.getUserReadingData("book").totalReadTime)
    }

    @Test
    fun absentRecordsAndSeparateBooksKeepTheirOwnMetadata() = runBlocking {
        val readAt = LocalDateTime.of(2026, 9, 8, 10, 0)
        withContext(Dispatchers.IO) {
            listOf("first", "second").mapIndexed { index, id ->
                launch {
                    source.updateUserReadingData(id) {
                        assertEquals(id, it.id)
                        assertTrue(it.currentChapterReadingProgressMap.isEmpty())
                        it.copyWithUpdatedChapterReadingProgress("$id-chapter", 0.5f)
                            .copy(totalReadTime = index + 1, lastReadTime = readAt, lastReadChapterTitle = id)
                    }
                }
            }.joinAll()
        }
        for ((index, id) in listOf("first", "second").withIndex()) {
            val saved = source.getUserReadingData(id)
            assertEquals(index + 1, saved.totalReadTime)
            assertEquals(mapOf("$id-chapter" to 0.5f), saved.currentChapterReadingProgressMap)
            assertEquals(readAt, saved.lastReadTime)
            assertEquals(id, saved.lastReadChapterTitle)
        }
    }
}
