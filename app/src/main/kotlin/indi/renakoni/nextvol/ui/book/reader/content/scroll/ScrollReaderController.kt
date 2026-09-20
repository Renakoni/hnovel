package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

/** Scroll-mode commands and settings; the chapter window and progress observer own their tasks. */
class ScrollReaderController(
    chapters: ReaderChapterLoader,
    readingData: BookReadingDataAccess,
    private val coroutineScope: CoroutineScope,
    private val settings: ContinuousScrollSettings,
    updateReadingProgress: (String, Float) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ReaderModeController {
    private var lazyColumnSize = IntSize(0, 0)

    override val requestedChapterId: String?
        get() = uiState.readingChapterId

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        setLazyColumnSize = {
            lazyColumnSize = it
        },
        writeProgressRightNow = ::writeProgressRightNow,
        retryChapter = { chaptersWindow.retryChapter(it) },
        onProgressRestored = { if (uiState.lazyListState === it) uiState.isRestoringProgress = false },
    )

    private val chaptersWindow = ScrollChapterWindow(
        uiState, chapters, readingData, settings, coroutineScope, { lazyColumnSize.height }, ioDispatcher,
    )
    private val progress = ScrollReadingProgress(
        uiState, coroutineScope, updateReadingProgress, { lazyColumnSize.height }, mainDispatcher,
    )

    init {
        coroutineScope.launch {
            settings.getFlow().distinctUntilChanged().collect {
                if (it) {
                    chaptersWindow.startContinuousObservation()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (!hasAdjacentChapters) {
                        coroutineScope.launch(mainDispatcher) {
                            uiState.readingChapterId?.let { id -> changeChapter(id) }
                        }
                    }
                } else {
                    chaptersWindow.stopContinuousObservation()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (hasAdjacentChapters) {
                        coroutineScope.launch(mainDispatcher) {
                            uiState.readingChapterId?.let { id -> changeChapter(id) }
                        }
                    }
                }
            }
        }
        progress.start()
    }

    override fun changeBookId(id: String) {
        chaptersWindow.changeBookId(id)
    }

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasNextChapter()) return
            changeChapter(readingChapterContent.nextChapter ?: return)
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasPrevChapter()) return
            changeChapter(readingChapterContent.prevChapter ?: return)
        }
    }

    override fun changeChapter(id: String) = chaptersWindow.changeChapter(id)

    private fun writeProgressRightNow() = progress.writeProgressRightNow()
}
