package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
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

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        setLazyColumnSize = {
            lazyColumnSize = it
        },
        writeProgressRightNow = ::writeProgressRightNow
    )

    private val chaptersWindow = ScrollChapterWindow(
        uiState, chapters, readingData, settings, coroutineScope, { lazyColumnSize.height }, ioDispatcher,
    )
    private val progress = ScrollReadingProgress(
        uiState, coroutineScope, updateReadingProgress, { lazyColumnSize.height }, ioDispatcher, mainDispatcher,
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
        uiState.bookId = id
    }

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasNextChapter()) return
            coroutineScope.launch {
                changeChapter(
                    id = readingChapterContent.nextChapter ?: return@launch
                )
            }
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasPrevChapter()) return
            coroutineScope.launch {
                changeChapter(
                    id = readingChapterContent.prevChapter ?: return@launch
                )
            }
        }
    }

    override fun changeChapter(id: String) = chaptersWindow.changeChapter(id)

    private fun writeProgressRightNow() = progress.writeProgressRightNow()
}
