package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeFactory
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDirectoryOwnershipTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun replacingBookCancelsThePreviousDirectoryCollection() {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val first = MutableSharedFlow<Result<BookVolumes, WebRequestError>>(extraBufferCapacity = 8)
        val second = MutableSharedFlow<Result<BookVolumes, WebRequestError>>(extraBufferCapacity = 8)
        val chapters = mockk<ChapterSource> {
            every { getBookVolumesFlow("first", any()) } returns flow {
                firstStarted.complete(Unit)
                try {
                    emitAll(first)
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            every { getBookVolumesFlow("second", any()) } returns flow {
                secondStarted.complete(Unit)
                emitAll(second)
            }
        }
        val dao = mockk<UserDataDao> {
            every { getFlow(any()) } returns kotlinx.coroutines.flow.flowOf(null)
            every { getFlow(UserDataPath.Reader.IsUsingFlipPage.path) } returns kotlinx.coroutines.flow.flowOf(null)
        }
        val controller = object : ReaderModeController {
            override val uiState: ContentUiState = mockk()
            override fun changeBookId(id: String) = Unit
            override fun changeChapter(id: String) = Unit
            override fun loadNextChapter() = Unit
            override fun loadPrevChapter() = Unit
        }
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), any()) } returns controller
        }
        val reader = ReaderViewModel(
            statsRepository = mockk(relaxed = true),
            chapterSource = chapters,
            readingData = mockk(relaxed = true),
            userDataRepository = UserDataRepository(dao),
            modeFactory = factory,
        )
        store.put("reader", reader)

        reader.bookId = "first"
        await { firstStarted.isCompleted }
        reader.bookId = "second"
        await { secondStarted.isCompleted }
        await { firstCancelled.isCompleted }

        second.tryEmit(Ok(BookVolumes("second", emptyList())))
        await { reader.uiState.bookVolumes?.get()?.bookId == "second" }
        first.tryEmit(Ok(BookVolumes("first", emptyList())))
        scheduler.runCurrent()

        assertEquals("second", reader.uiState.bookVolumes?.get()?.bookId)
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }
}
