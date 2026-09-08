package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ScrollModeContractTest {
    private val env = ModeTestEnvironment()
    private val continuous = MutableStateFlow(false)
    private val progress = mutableListOf<Pair<String, Float>>()
    private lateinit var mode: ScrollReaderController

    @After fun tearDown() = env.close()

    private fun open(continuousScrolling: Boolean = false, id: String = "requested") {
        continuous.value = continuousScrolling
        val settings = object : ContinuousScrollSettings {
            override fun getFlow() = continuous
            override suspend fun isEnabled() = continuous.value
        }
        mode = ScrollReaderController(
            env.loader, env.records, env.scope, settings,
            { chapter, value -> progress += chapter to value },
            ioDispatcher = env.dispatcher, mainDispatcher = env.dispatcher,
        )
        // Let the initial settings observation run before an explicit chapter request.
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter(id)
        env.runCurrent()
    }

    @Test
    fun singleChapterRestoresProgressInsideTheWriteThenPreloadsWithDefaultPriority() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.6f))
        open()
        env.events.clear()
        env.emit("requested", Ok(env.chapter("payload", "prev", "next", "Title")))
        assertEquals("requested", mode.uiState.contentList[1]!!.first)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals(listOf("payload", "Title", "prev", "next"), listOf(content.id, content.title, content.prevChapter, content.nextChapter))
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertEquals("requested", env.records.writes.single().lastReadChapterId)
        assertEquals("Title", env.records.writes.single().lastReadChapterTitle)
        assertNotNull(env.records.writes.single().lastReadTime)
        assertEquals(WebDataSourcePriority.Default, env.chapters.requests.single().priority)
        assertEquals(WebDataSourcePriority.Default, env.chapters.preloads.single().priority)
        assertEquals(listOf("render/Title", "write/start/book", "write/end/requested", "preload/start/next", "preload/end/next"), env.events)
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
    }

    @Test
    fun displayPrecedesSuspendedPersistenceAndProgressRestorationWaitsForTheTransform() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.4f))
        open()
        val gate = CompletableDeferred<Unit>()
        env.records.writeGate = gate
        env.emit("requested", Ok(env.chapter("requested", next = "next")))
        assertNotNull(mode.uiState.readingChapterContent)
        assertEquals(0f, mode.uiState.readingProgress)
        assertTrue(env.chapters.preloads.isEmpty())
        gate.complete(Unit)
        env.runCurrent()
        assertEquals(0.4f, mode.uiState.readingProgress)
        assertEquals("next", env.chapters.preloads.single().chapterId)
    }

    @Test
    fun laterSuccessRestoresStoredProgressAgainAndErrorReplacesContentWithoutWriting() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.uiState.readingProgress = 0.8f
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.2f))
        env.emit("requested", Ok(env.chapter("requested", title = "remote")))
        assertEquals(0.2f, mode.uiState.readingProgress)
        val error = Err(WebRequestError("offline", "failed"))
        env.emit("requested", error)
        assertEquals(error, mode.uiState.readingChapterContent)
        assertEquals(2, env.records.writes.size)
    }

    @Test
    fun continuousModeWaitsForPreloadBeforeSubscribingToPreviousThenNext() {
        open(continuousScrolling = true)
        val gate = CompletableDeferred<Unit>()
        env.chapters.preloadGate = gate
        env.events.clear()
        env.emit("requested", Ok(env.chapter("requested", "prev", "next")))
        assertEquals(listOf("requested"), env.chapters.requests.map { it.chapterId })
        gate.complete(Unit)
        env.runCurrent()
        assertEquals(listOf("requested", "prev", "next"), env.chapters.requests.map { it.chapterId })
        assertEquals(listOf("render/requested", "write/start/book", "write/end/requested", "preload/start/next", "preload/end/next", "subscribe/prev", "subscribe/next"), env.events)
        env.emit("prev", Ok(env.chapter("prev")))
        val nextError = Err(WebRequestError("offline", "next failed"))
        env.emit("next", nextError)
        assertEquals(1, env.records.writes.size)
        assertEquals(nextError, mode.uiState.contentList[2]!!.second)
    }

    @Test
    fun selfLinksAndDuplicateAdjacentIdsDoNotCreateExtraSubscriptions() {
        open(continuousScrolling = true)
        env.emit("requested", Ok(env.chapter("requested", "requested", "requested")))
        assertEquals(1, env.chapters.requests.size)
        env.emit("requested", Ok(env.chapter("requested", "duplicate", "duplicate")))
        assertEquals(1, env.chapters.requests.size)
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
        // Preloading is intentionally not subject to the adjacency guard.
        assertEquals(listOf("requested", "duplicate"), env.chapters.preloads.map { it.chapterId })
    }

    @Test
    fun cachedRefreshCancelsAndRecreatesBothAdjacentSubscriptions() {
        open(continuousScrolling = true)
        env.emit("requested", Ok(env.chapter("requested", "prev", "next")))
        env.events.clear()
        env.emit("requested", Ok(env.chapter("requested", "prev2", "next2")))
        assertEquals(listOf("requested", "prev2", "next2"), env.chapters.active.map { it.chapterId })
        assertTrue("cancel/prev" in env.events)
        assertTrue("cancel/next" in env.events)
        assertTrue(env.events.indexOf("preload/end/next2") < env.events.indexOf("subscribe/prev2"))
        assertTrue(env.events.indexOf("subscribe/prev2") < env.events.indexOf("subscribe/next2"))
    }

    @Test
    fun crossingIntoTheNextChapterShiftsCachedSlotsBeforeResubscribing() {
        open(continuousScrolling = true, id = "current")
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        env.emit("next", Ok(env.chapter("next", "current", "later")))
        val oldCurrent = mode.uiState.contentList[1]
        val oldNext = mode.uiState.contentList[2]
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        env.runCurrent()
        viewport.items.value = listOf(item("next", 0, 500))
        env.runCurrent()
        assertEquals("next", mode.uiState.readingChapterId)
        assertSame(oldCurrent, mode.uiState.contentList[0])
        assertSame(oldNext, mode.uiState.contentList[1])
        assertNull(mode.uiState.contentList[2])
        assertEquals(listOf("current", "next"), env.chapters.active.map { it.chapterId })
        assertEquals("next", env.records.writes.last().lastReadChapterId)
        env.emit("next", Ok(env.chapter("next", "current", "later")))
        assertEquals(listOf("current", "next", "later"), env.chapters.active.map { it.chapterId })
        assertEquals(listOf("next"), env.chapters.preloads.map { it.chapterId })
    }

    @Test
    fun crossingIntoThePreviousChapterRequiresOneViewportOfNegativeOffset() {
        open(continuousScrolling = true, id = "current")
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        env.emit("prev", Ok(env.chapter("prev", "earlier", "current")))
        mode.uiState.setLazyColumnSize(IntSize(100, 200))
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        env.runCurrent()
        viewport.items.value = listOf(item("prev", -199, 500))
        env.runCurrent()
        assertEquals("current", mode.uiState.readingChapterId)
        viewport.items.value = listOf(item("prev", -200, 500))
        env.runCurrent()
        assertEquals("prev", mode.uiState.readingChapterId)
        assertEquals(listOf("current", "prev"), env.chapters.active.map { it.chapterId })
    }

    @Test
    fun turningOffContinuousScrollingCancelsOldAdjacentSubscriptions() {
        open(continuousScrolling = true)
        env.emit("requested", Ok(env.chapter("requested", "prev", "next")))
        env.emit("prev", Ok(env.chapter("prev")))
        continuous.value = false
        env.runCurrent()
        assertNull(mode.uiState.contentList[0])
        assertEquals(listOf("requested"), env.chapters.active.map { it.chapterId })
        env.emit("next", Ok(env.chapter("next")))
        assertNull(mode.uiState.contentList[2])
        env.close()
        assertTrue(env.chapters.active.isEmpty())
    }

    @Test
    fun stoppingWhileCurrentChapterIsWaitingDoesNotRecreateAdjacentSubscriptions() {
        open(continuousScrolling = true)
        val preloadGate = CompletableDeferred<Unit>()
        env.chapters.preloadGate = preloadGate

        env.emit("requested", Ok(env.chapter("requested", "prev", "next")))
        continuous.value = false
        env.runCurrent()
        assertEquals(listOf("requested"), env.chapters.active.map { it.chapterId })

        preloadGate.complete(Unit)
        env.runCurrent()

        assertEquals(listOf("requested"), env.chapters.active.map { it.chapterId })
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
    }

    @Test
    fun stoppingScrollRecalculatesClampedProgressAndExplicitStopWritesSynchronously() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.uiState.setLazyColumnSize(IntSize(100, 100))
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        env.runCurrent()
        viewport.items.value = listOf(item("requested", -20, 400))
        viewport.scrolling.value = true
        env.runCurrent()
        viewport.scrolling.value = false
        env.runCurrent()
        assertEquals(0.3f, mode.uiState.readingProgress)
        assertEquals("requested" to 0.3f, progress.last())
        viewport.items.value = listOf(item("requested", -500, 400))
        viewport.scrolling.value = true
        env.runCurrent()
        viewport.scrolling.value = false
        env.runCurrent()
        assertEquals(1f, mode.uiState.readingProgress)
        progress.clear()
        mode.uiState.writeProgressRightNow()
        assertEquals(listOf("requested" to 1f), progress)
        env.close()
        assertEquals(listOf("requested" to 1f), progress)
    }

    private class Viewport {
        val items = mutableStateOf<List<LazyListItemInfo>>(emptyList())
        val scrolling = mutableStateOf(false)
        private val layout = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } answers { items.value } }
        val state = mockk<LazyListState> {
            every { layoutInfo } returns layout
            every { firstVisibleItemScrollOffset } returns 0
            every { isScrollInProgress } answers { scrolling.value }
        }
    }

    private fun item(chapterId: String, top: Int, height: Int): LazyListItemInfo = mockk {
        every { key } returns chapterId
        every { offset } returns top
        every { size } returns height
    }
}
