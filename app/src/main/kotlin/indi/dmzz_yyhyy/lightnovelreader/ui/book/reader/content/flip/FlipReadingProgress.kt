package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Owns pager observation and pending progress, using the reader's existing task lifetime. */
internal class FlipReadingProgress(
    private val uiState: MutableFlipPageContentUiState,
    private val readingData: BookReadingDataAccess,
    private val coroutineScope: CoroutineScope,
    private val updateReadingProgress: (String, Float) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var notRecoveredProgress = 0f
    private var collectProgressJob: Job? = null
    private var restorationJob: Job? = null

    fun start() {
        coroutineScope.launch(ioDispatcher) {
            snapshotFlow { uiState.pagerState }.collect { pagerState ->
                collectProgressJob?.cancel()
                collectProgressJob = coroutineScope.launch(ioDispatcher) {
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        val progress = if (pagerState.pageCount == 0) 0f
                        else ((page + 1) / pagerState.pageCount.toFloat()).coerceIn(0f, 1f)
                        uiState.readingProgress = progress
                        uiState.readingChapterContent?.onOk {
                            updateReadingProgress(it.id, progress)
                        }
                    }
                }
            }
        }
    }

    fun updatePagerState(pagerState: PagerState) {
        uiState.pagerState = pagerState
        restorationJob?.cancel()
        restorationJob = null
        if (pagerState.pageCount == 0) return
        val progressToRestore = when {
            notRecoveredProgress > 0f -> notRecoveredProgress
            uiState.readingProgress > 0f -> uiState.readingProgress
            else -> return
        }
        restorationJob = coroutineScope.launch {
            if (uiState.pagerState !== pagerState) return@launch
            val pageCount = pagerState.pageCount
            if (pageCount == 0 || uiState.pagerState !== pagerState) return@launch
            val recovered = progressToRestore.coerceIn(0f, 1f)
            val target = ((pageCount * recovered).roundToInt() - 1)
                .coerceIn(0, pageCount - 1)
            if (uiState.pagerState !== pagerState) return@launch
            pagerState.scrollToPage(target)
            if (notRecoveredProgress == progressToRestore) {
                notRecoveredProgress = 0f
            }
        }
    }

    fun resetForChapter() {
        restorationJob?.cancel()
        restorationJob = null
        notRecoveredProgress = 0f
        uiState.readingProgress = 0f
    }

    fun recoverForChapter(id: String) {
        coroutineScope.launch(ioDispatcher) {
            readingData.getUserReadingData(uiState.bookId).let {
                notRecoveredProgress = it.currentChapterReadingProgressMap[id] ?: 0f
            }
        }
    }
}
