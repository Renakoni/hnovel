package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.app.Application
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableIntStateOf
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class FlipModeContractTest {
    private val env = ModeTestEnvironment()
    private val progress = mutableListOf<Pair<String, Float>>()
    private val mode = FlipReaderController(
        env.loader, env.records, env.scope, { id, value -> progress += id to value },
        ioDispatcher = env.dispatcher,
    )

    @After fun tearDown() = env.close()

    private fun open(id: String = "requested") {
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter(id)
        env.runCurrent()
    }

    @Test
    fun successMapsThePayloadButPersistsTheRequestedIdBeforePreloading() {
        open()
        env.events.clear()
        env.emit("requested", Ok(env.chapter("payload", "prev", "next", "Title")))
        assertEquals("requested", mode.uiState.readingChapterId)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals(listOf("payload", "Title", "prev", "next"), listOf(content.id, content.title, content.prevChapter, content.nextChapter))
        assertEquals("requested", env.records.writes.single().lastReadChapterId)
        assertEquals("Title", env.records.writes.single().lastReadChapterTitle)
        assertNotNull(env.records.writes.single().lastReadTime)
        assertEquals(WebDataSourcePriority.High, env.chapters.requests.single().priority)
        assertEquals(WebDataSourcePriority.Default, env.chapters.preloads.single().priority)
        assertEquals(listOf("render/Title", "write/start/book", "write/end/requested", "preload/start/next", "preload/end/next"), env.events)
    }

    @Test
    fun persistenceSuspensionDoesNotDelayDisplayButDoesDelayPreload() {
        open()
        val gate = CompletableDeferred<Unit>()
        env.records.writeGate = gate
        env.emit("requested", Ok(env.chapter("requested", next = "next")))
        assertEquals("requested", mode.uiState.readingChapterContent!!.get()!!.id)
        assertTrue(env.chapters.preloads.isEmpty())
        gate.complete(Unit)
        env.runCurrent()
        assertEquals("next", env.chapters.preloads.single().chapterId)
    }

    @Test
    fun aSuspendedOlderMetadataWriteCannotOverwriteTheNewerChapter() {
        open("first")
        val firstWriteGate = CompletableDeferred<Unit>()
        env.records.nonCancellableWriteGates += firstWriteGate

        env.emit("first", Ok(env.chapter("first")))
        mode.changeChapter("second")
        env.runCurrent()
        env.emit("second", Ok(env.chapter("second")))
        firstWriteGate.complete(Unit)
        env.runCurrent()

        assertEquals("second", env.records.data.lastReadChapterId)
        assertEquals(listOf("first", "second"), env.records.writes.map { it.lastReadChapterId })
    }

    @Test
    fun aLaterErrorReplacesCachedContentWithoutAnotherWriteOrPreload() {
        open()
        env.emit("requested", Ok(env.chapter("requested", next = "next")))
        val error = Err(WebRequestError("offline", "remote failed"))
        env.emit("requested", error)
        assertEquals(error, mode.uiState.readingChapterContent)
        assertEquals(1, env.records.writes.size)
        assertEquals(1, env.chapters.preloads.size)
    }

    @Test
    fun changingChapterCancelsOlderSubscriptionAndIgnoresItsLateResult() {
        open("first")
        mode.changeChapter("second")
        env.runCurrent()
        env.emit("second", Ok(env.chapter("second")))
        env.emit("first", Ok(env.chapter("first")))
        assertEquals("second", mode.uiState.readingChapterId)
        assertEquals(listOf("second"), env.chapters.active.map { it.chapterId })
        env.close()
        assertTrue(env.chapters.active.isEmpty())
        val writes = env.records.writes.size
        env.emit("first", Ok(env.chapter("late")))
        assertEquals(writes, env.records.writes.size)
    }

    @Test
    fun changingChapterDiscardsTheOlderPendingProgressRecovery() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGate = gate
        env.records.data = env.records.data.copy(
            currentChapterReadingProgressMap = mapOf("first" to 0.2f, "second" to 0.8f),
        )
        open("first")
        mode.changeChapter("second")
        env.runCurrent()

        gate.complete(Unit)
        env.runCurrent()
        val targets = mutableListOf<Int>()
        mode.updatePagerState(pager(10, targets = targets))
        env.runCurrent()
        assertEquals(listOf(7), targets)
    }

    @Test
    fun blankRequestsKeepTheCurrentChapterAndProgress() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.uiState.readingProgress = 0.6f
        mode.changeChapter(" ")
        env.runCurrent()
        assertEquals("requested", mode.uiState.readingChapterId)
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertEquals(1, env.chapters.requests.size)
    }

    @Test
    fun storedProgressIsConsumedWhenANonemptyPagerArrivesAndUsesTheExistingRounding() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.6f))
        open()
        val targets = mutableListOf<Int>()
        mode.updatePagerState(pager(0, targets = targets))
        env.runCurrent()
        assertTrue(targets.isEmpty())
        mode.updatePagerState(pager(5, targets = targets))
        env.runCurrent()
        assertEquals(listOf(2), targets)
    }

    @Test
    fun lateStoredProgressRestoresTheAlreadyCreatedPagerImmediately() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGates += gate
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.75f))
        open()
        val targets = mutableListOf<Int>()
        mode.updatePagerState(pager(4, targets = targets))
        env.runCurrent()
        gate.complete(Unit)
        env.runCurrent()
        assertEquals(listOf(2), targets)
    }

    @Test
    fun lateStoredProgressDoesNotOverrideAUserPageChange() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGate = gate
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.75f))
        open()
        val targets = mutableListOf<Int>()
        val page = mutableIntStateOf(0)
        mode.updatePagerState(pager(4, page, targets))
        env.runCurrent()
        page.intValue = 1
        env.runCurrent()
        gate.complete(Unit)
        env.runCurrent()
        assertTrue(targets.isEmpty())
    }

    @Test
    fun lateStoredProgressDoesNotOverrideAnInFlightPageChange() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGate = gate
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.75f))
        open()
        val targets = mutableListOf<Int>()
        val pager = mockk<PagerState> {
            every { pageCount } returns 4
            every { settledPage } returns 0
            every { currentPage } returns 1
            every { targetPage } returns 1
            every { isScrollInProgress } returns true
            coEvery { scrollToPage(any(), any()) } answers { targets += firstArg<Int>() }
        }
        mode.updatePagerState(pager)
        env.runCurrent()
        gate.complete(Unit)
        env.runCurrent()
        assertTrue(targets.isEmpty())
    }

    @Test
    fun replacingThePagerCancelsItsPreviousProgressObserver() {
        open()
        env.emit("requested", Ok(env.chapter("payload")))
        val oldPage = mutableIntStateOf(0)
        val newPage = mutableIntStateOf(1)
        mode.updatePagerState(pager(4, oldPage))
        env.runCurrent()
        mode.updatePagerState(pager(4, newPage))
        env.runCurrent()
        progress.clear()
        oldPage.intValue = 3
        env.runCurrent()
        assertTrue(progress.isEmpty())
        newPage.intValue = 2
        env.runCurrent()
        assertEquals(listOf("payload" to 0.75f), progress)
    }

    @Test
    fun queuedRestorationUsesTheCurrentPagerPageCountAndTarget() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.6f))
        open()
        val oldTargets = mutableListOf<Int>()
        val newTargets = mutableListOf<Int>()
        mode.updatePagerState(pager(5, targets = oldTargets))
        mode.updatePagerState(pager(2, targets = newTargets))
        env.runCurrent()
        assertTrue(oldTargets.isEmpty())
        assertEquals(listOf(0), newTargets)
    }

    @Test
    fun emptyPagerDuringRepaginationPreservesProgressUntilTheNewPagesAreReady() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.updatePagerState(pager(10, mutableIntStateOf(5)))
        env.runCurrent()
        assertEquals(0.6f, mode.uiState.readingProgress)
        progress.clear()

        mode.updatePagerState(pager(0))
        env.runCurrent()
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertTrue(progress.isEmpty())

        val page = mutableIntStateOf(0)
        val targets = mutableListOf<Int>()
        val rebuilt = pager(20, page, targets)
        coEvery { rebuilt.scrollToPage(any(), any()) } answers {
            val target = firstArg<Int>()
            targets += target
            page.intValue = target
        }
        mode.updatePagerState(rebuilt)
        env.runCurrent()
        assertEquals(listOf(11), targets)
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it == "requested" to 0.6f })
    }

    @Test
    fun replacingQueuedRestorationWithAnEmptyPagerDoesNotScrollTheOldPager() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.6f))
        open()
        val oldTargets = mutableListOf<Int>()
        mode.updatePagerState(pager(5, targets = oldTargets))
        mode.updatePagerState(pager(0))
        env.runCurrent()
        assertTrue(oldTargets.isEmpty())
    }

    private fun pager(count: Int, page: androidx.compose.runtime.MutableIntState = mutableIntStateOf(0), targets: MutableList<Int> = mutableListOf()): PagerState = mockk {
        every { pageCount } returns count
        every { settledPage } answers { page.intValue }
        every { currentPage } answers { page.intValue }
        every { targetPage } answers { page.intValue }
        every { isScrollInProgress } returns false
        coEvery { scrollToPage(any(), any()) } answers { targets += firstArg<Int>() }
    }
}
