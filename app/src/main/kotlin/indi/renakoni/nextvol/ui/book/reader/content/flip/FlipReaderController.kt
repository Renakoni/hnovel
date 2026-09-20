package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.pager.PagerState
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class FlipReaderController(
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (String, Float) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReaderModeController {
    private var latestRequestedChapterId: String? = null
    override val requestedChapterId: String? get() = latestRequestedChapterId

    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        updatePageState = ::updatePagerState,
        updateAnchoredPageState = { progress.updatePagerState(it, anchored = true) },
        commitPendingChapter = ::commitPendingChapter,
        failPendingChapter = ::failPendingChapter,
        retryPendingChapter = {
            uiState.pendingChapter?.let { loadChapter(it.chapterId, it.entry) }
        },
        cancelPendingChapter = ::cancelPendingChapter,
    )
    private val progress = FlipReadingProgress(uiState, readingData, coroutineScope, updateReadingProgress, ioDispatcher)
    private class Request(val bookId: String, val chapterId: String, var committed: Boolean)
    @Volatile private var request: Request? = null
    private var chapterLoadJob: Job? = null
    private val readingMetadataMutex = Mutex()

    init { progress.start() }

    private fun isCurrent(expected: Request) = request === expected && coroutineScope.isActive

    fun updatePagerState(pagerState: PagerState) = progress.updatePagerState(pagerState)

    override fun changeBookId(id: String) {
        if (uiState.bookId == id) return
        request = null
        chapterLoadJob?.cancel()
        latestRequestedChapterId = null
        uiState.pendingChapter = null
        progress.resetForChapter()
        uiState.bookId = id
        uiState.readingChapterId = null
        uiState.readingChapterContent = null
        uiState.pagerState = PagerState { 0 }
    }

    override fun loadNextChapter() = loadAdjacent(ChapterEntry.Start)
    override fun loadPrevChapter() = loadAdjacent(ChapterEntry.End)

    private fun loadAdjacent(entry: ChapterEntry) {
        val content = uiState.readingChapterContent?.get() ?: return
        val id = (if (entry == ChapterEntry.Start) content.nextChapter else content.prevChapter) ?: return
        if (id == uiState.readingChapterId) return
        val pending = uiState.pendingChapter
        if (pending?.chapterId == id && pending.result?.isErr != true) return
        loadChapter(id, entry)
    }

    override fun changeChapter(id: String) {
        if (id.isBlank()) return
        latestRequestedChapterId = id
        progress.resetForChapter()
        uiState.pendingChapter = null
        uiState.readingChapterId = id
        uiState.readingChapterContent = null
        uiState.pagerState = PagerState { 0 }
        loadChapter(id, ChapterEntry.Restore)
        progress.recoverForChapter(id, uiState.bookId)
    }

    private fun loadChapter(id: String, entry: ChapterEntry) {
        chapterLoadJob?.cancel()
        val expected = Request(uiState.bookId, id, committed = entry == ChapterEntry.Restore).also { request = it }
        if (!expected.committed) uiState.pendingChapter = FlipChapterTransition(id, entry)
        chapterLoadJob = coroutineScope.launch {
            chapters.load(id, expected.bookId, WebDataSourcePriority.High).flowOn(ioDispatcher).collect { result ->
                if (!isCurrent(expected)) return@collect
                if (!expected.committed) {
                    // Keep the displayed chapter and its progress until the UI has real pages.
                    uiState.pendingChapter = FlipChapterTransition(id, entry, result)
                } else {
                    uiState.readingChapterId = id
                    uiState.readingChapterContent = result
                    result.onOk { persistAndPreload(expected, it) }
                }
            }
        }
    }

    private fun commitPendingChapter(pending: FlipChapterTransition, pager: PagerState): Boolean {
        val expected = request ?: return false
        val content = pending.result?.get() ?: return false
        if (!isCurrent(expected) || uiState.pendingChapter !== pending || pager.pageCount == 0) return false
        expected.committed = true
        progress.resetForChapter()
        latestRequestedChapterId = expected.chapterId
        uiState.readingChapterId = expected.chapterId
        uiState.readingChapterContent = pending.result
        uiState.pendingChapter = null
        progress.updatePagerState(pager, anchored = true)
        coroutineScope.launch { persistAndPreload(expected, content) }
        return true
    }

    private fun failPendingChapter(pending: FlipChapterTransition, error: WebRequestError) {
        if (uiState.pendingChapter === pending) {
            uiState.pendingChapter = FlipChapterTransition(pending.chapterId, pending.entry, Err(error))
        }
    }

    private fun cancelPendingChapter() {
        if (uiState.pendingChapter == null) return
        request = null
        chapterLoadJob?.cancel()
        uiState.pendingChapter = null
    }

    private suspend fun persistAndPreload(expected: Request, content: ChapterContentUiState) {
        readingMetadataMutex.withLock {
            if (!isCurrent(expected)) return
            withContext(ioDispatcher) {
                readingData.updateUserReadingData(expected.bookId) { data ->
                    if (!isCurrent(expected)) data else data.copy(
                        lastReadTime = LocalDateTime.now(),
                        lastReadChapterId = expected.chapterId,
                        lastReadChapterTitle = content.title,
                    )
                }
            }
        }
        if (!isCurrent(expected)) return
        content.nextChapter?.let { withContext(ioDispatcher) { chapters.preload(it, expected.bookId) } }
    }
}
