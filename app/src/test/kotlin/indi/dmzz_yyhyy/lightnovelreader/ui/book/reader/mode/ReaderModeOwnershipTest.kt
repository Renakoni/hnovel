package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode

import android.app.Application
import androidx.lifecycle.ViewModelStore
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderMode
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeFactory
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderModeOwnershipTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun modeReplacementKeepsTheReaderScopeAndViewModelClearCancelsAllItsModeTasks() {
        val flip = MutableStateFlow<String?>(null)
        val dao = mockk<UserDataDao> {
            every { getFlow(any()) } returns flowOf(null)
            every { getFlow(UserDataPath.Reader.IsUsingFlipPage.path) } returns flip
        }
        val events = mutableListOf<String>()
        val scopes = mutableListOf<CoroutineScope>()
        val tasks = mutableListOf<Job>()
        val states = mutableListOf<ContentUiState>()
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), any()) } answers {
                val mode = firstArg<ReaderMode>()
                val scope = secondArg<CoroutineScope>()
                scopes += scope
                tasks += scope.launch {
                    try { awaitCancellation() } finally { events += "stop/$mode" }
                }
                object : ReaderModeController {
                    private var displayedChapter: String? = null
                    override val uiState = mockk<ContentUiState> {
                        every { readingChapterId } answers { displayedChapter }
                    }.also(states::add)
                    override fun changeBookId(id: String) { events += "$mode/book/$id" }
                    override fun changeChapter(id: String) { displayedChapter = id; events += "$mode/chapter/$id" }
                    override fun loadNextChapter() { displayedChapter = "$displayedChapter-next"; events += "$mode/next" }
                    override fun loadPrevChapter() { events += "$mode/prev" }
                }
            }
        }
        val reader = ReaderViewModel(mockk(), mockk(), mockk(), UserDataRepository(dao), factory)
        store.put("reader", reader)
        reader.changeChapter("initial")
        scheduler.runCurrent()
        assertSame(states.single(), reader.uiState.contentUiState)
        flip.value = "true"
        scheduler.runCurrent()
        reader.nextChapter()
        assertEquals("initial-next", reader.uiState.contentUiState!!.readingChapterId)
        flip.value = "false"
        scheduler.runCurrent()
        assertEquals("initial-next", reader.uiState.contentUiState!!.readingChapterId)
        reader.changeChapter("direct")
        assertEquals(listOf("Scroll/book/", "Scroll/chapter/initial", "Flip/book/", "Flip/chapter/initial", "Flip/next", "Scroll/book/", "Scroll/chapter/initial-next", "Scroll/chapter/direct"), events)
        assertSame(states.last(), reader.uiState.contentUiState)
        assertEquals(3, tasks.size)
        assertTrue(tasks.all { it.isActive })
        scopes.forEach { assertSame(scopes.first(), it) }
        store.clear()
        scheduler.runCurrent()
        assertTrue(tasks.all { it.isCancelled })
        assertEquals(3, events.count { it.startsWith("stop/") })
        assertFalse(scopes.first().coroutineContext[Job]!!.isActive)
    }
}
