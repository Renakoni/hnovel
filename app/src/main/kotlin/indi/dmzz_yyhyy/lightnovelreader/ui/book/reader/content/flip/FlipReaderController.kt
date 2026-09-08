package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

class FlipReaderController(
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (String, Float) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReaderModeController {
    private var latestRequestedChapterId: String? = null

    override val requestedChapterId: String?
        get() = latestRequestedChapterId

    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        updatePageState = ::updatePagerState
    )

    private val progress = FlipReadingProgress(
        uiState, readingData, coroutineScope, updateReadingProgress, ioDispatcher,
    )

    private var chapterLoadJob: Job? = null
    private var chapterRequestGeneration = 0L
    private val readingMetadataMutex = Mutex()

    init { progress.start() }

    fun updatePagerState(pagerState: PagerState) = progress.updatePagerState(pagerState)

    override fun changeBookId(id: String) {
        if (uiState.bookId != id) {
            chapterLoadJob?.cancel()
            chapterRequestGeneration++
            progress.resetForChapter()
        }
        uiState.bookId = id
    }

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk {
            it.nextChapter?.let { id ->
                changeChapter(
                    id = id
                )
            }
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk {
            it.prevChapter?.let { id ->
                changeChapter(
                    id = id
                )
            }
        }
    }

    override fun changeChapter(id: String) {
        if (id.isBlank()) {
            Log.e("FlipPageContentViewModel", "a id less than 0 was transferred")
            return
        }
        latestRequestedChapterId = id
        progress.resetForChapter()
        chapterLoadJob?.cancel()
        val requestGeneration = ++chapterRequestGeneration
        val bookId = uiState.bookId
        chapterLoadJob = coroutineScope.launch {
            chapters.load(
                id,
                bookId,
                WebDataSourcePriority.High
            ).collect { result ->
                if (requestGeneration != chapterRequestGeneration) return@collect
                uiState.readingChapterId = id
                uiState.readingChapterContent = result
                result.onOk { content ->
                    readingMetadataMutex.withLock {
                        if (requestGeneration != chapterRequestGeneration) return@withLock
                        readingData.updateUserReadingData(bookId) {
                            it.copy(
                                lastReadTime = LocalDateTime.now(),
                                lastReadChapterId = id,
                                lastReadChapterTitle = content.title
                            )
                        }
                    }
                    if (requestGeneration != chapterRequestGeneration) return@onOk
                    content.nextChapter?.let {
                        chapters.preload(
                            it,
                            bookId
                        )
                    }
                }
            }
        }
        progress.recoverForChapter(id, bookId)
    }
}
