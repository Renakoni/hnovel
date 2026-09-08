package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDirectoryProgressTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))
    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun progressUsesTheExistingDirectoryCollectionWithoutWaitingForIt() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val collectionCount = AtomicInteger()
            val directory = Channel<Pair<BookVolumes, CompletableDeferred<Unit>>>()
            val chapters = mockk<ChapterSource> {
                every { getBookVolumesFlow("book", any()) } returns flow {
                    collectionCount.incrementAndGet()
                    started.complete(Unit)
                    for ((volumes, applied) in directory) {
                        emit(Ok(volumes))
                        applied.complete(Unit)
                    }
                }
            }
            val data = AtomicReference(UserReadingData("book", readingProgress = 0.4f))
            val writes = Channel<UserReadingData>(Channel.UNLIMITED)
            val readingData = mockk<BookReadingDataAccess>(relaxed = true)
            coEvery { readingData.updateUserReadingData("book", any()) } coAnswers {
                val updated = secondArg<(UserReadingData) -> UserReadingData>()(data.get())
                data.set(updated)
                writes.send(updated)
            }
            coEvery { readingData.getUserReadingData("book") } answers { data.get() }
            val saveProgress = slot<(String, Float) -> Unit>()
            val contentState = object : ContentUiState by mockk(relaxed = true) {
                override val readingChapterContent: Result<ChapterContentUiState, WebRequestError>? =
                    Ok(ChapterContentUiState("chapter", "Title", emptyList(), null, null))
            }
            val controller = mockk<ReaderModeController>(relaxed = true) {
                every { uiState } returns contentState
            }
            val factory = mockk<ReaderModeFactory> {
                every { create(any(), any(), any(), capture(saveProgress)) } returns controller
            }
            val dao = mockk<UserDataDao>(relaxed = true) {
                every { getFlow(any()) } returns flowOf(null)
            }
            coEvery { dao.get(any()) } returns null
            val reader = ReaderViewModel(mockk(relaxed = true), chapters, readingData, UserDataRepository(dao), factory)
            store.put("reader", reader)
            scheduler.runCurrent()
            reader.bookId = "book"
            started.await()

            saveProgress.captured("chapter", 0.5f)
            assertEquals(0.4f, writes.receive().readingProgress)
            for ((count, expected) in listOf(0 to 0.4f, 2 to 0.25f, 4 to 0.125f)) {
                val applied = CompletableDeferred<Unit>()
                directory.send(BookVolumes("book", listOf(Volume("volume", "Title", List(count) { ChapterInformation("$it", "Chapter $it") }))) to applied)
                applied.await()
                saveProgress.captured("chapter", 0.5f)
                assertEquals(expected, writes.receive().readingProgress)
            }
            assertEquals(1, collectionCount.get())
            verify(exactly = 1) { chapters.getBookVolumesFlow("book", any()) }
        }
    }
}
