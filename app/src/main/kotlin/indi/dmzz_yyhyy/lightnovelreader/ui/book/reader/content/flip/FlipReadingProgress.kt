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
    private var currentPagerState: PagerState? = null
    private var initialPagerPage: Int? = null
    private var restorationApplied = false
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0L
    @Volatile private var progressPagerState: PagerState? = null
    @Volatile private var recoveryPending = false

    fun start() {
        coroutineScope.launch(ioDispatcher) {
            snapshotFlow { uiState.pagerState }.collect { pagerState ->
                collectProgressJob?.cancel()
                collectProgressJob = coroutineScope.launch(ioDispatcher) {
                    snapshotFlow { pagerState.settledPage }.collect progress@{ page ->
                        // An empty pager is a layout transition, not a new reading position.
                        val pageCount = pagerState.pageCount
                        if (pageCount == 0 || uiState.pagerState !== pagerState ||
                            progressPagerState !== pagerState
                        ) return@progress
                        val progress = ((page + 1) / pageCount.toFloat()).coerceIn(0f, 1f)
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
        progressPagerState = null
        currentPagerState = pagerState
        uiState.pagerState = pagerState
        restorationJob?.cancel()
        restorationJob = null
        if (pagerState.pageCount > 0 && initialPagerPage == null)
            initialPagerPage = pagerState.settledPage
        if (!recoveryPending) restorePendingProgress(allowCurrentProgress = true)
    }

    fun resetForChapter() {
        restorationJob?.cancel()
        restorationJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryGeneration++
        recoveryPending = false
        progressPagerState = null
        notRecoveredProgress = 0f
        currentPagerState = null
        initialPagerPage = null
        restorationApplied = false
        uiState.readingProgress = 0f
    }

    fun recoverForChapter(id: String, bookId: String = uiState.bookId) {
        restorationJob?.cancel()
        restorationJob = null
        recoveryJob?.cancel()
        val generation = ++recoveryGeneration
        recoveryPending = true
        progressPagerState = null
        recoveryJob = coroutineScope.launch(ioDispatcher) {
            readingData.getUserReadingData(bookId).let {
                if (generation != recoveryGeneration) return@let
                notRecoveredProgress = it.currentChapterReadingProgressMap[id] ?: 0f
                recoveryPending = false
                coroutineScope.launch {
                    restorePendingProgress(expectedRecoveryGeneration = generation)
                }
            }
        }
    }

    private fun restorePendingProgress(
        allowCurrentProgress: Boolean = false,
        expectedRecoveryGeneration: Long? = null,
    ) {
        if (expectedRecoveryGeneration != null && expectedRecoveryGeneration != recoveryGeneration) return
        val pagerState = currentPagerState ?: return
        if (pagerState.pageCount == 0 || (!allowCurrentProgress && restorationApplied)) {
            if (pagerState.pageCount == 0 || restorationApplied) progressPagerState = pagerState
            return
        }
        val hasRecoveredProgress = notRecoveredProgress > 0f
        if (!hasRecoveredProgress && (!allowCurrentProgress || uiState.readingProgress <= 0f)) {
            progressPagerState = pagerState
            return
        }
        if (hasRecoveredProgress && initialPagerPage != null &&
            (pagerState.isScrollInProgress ||
                pagerState.currentPage != initialPagerPage ||
                pagerState.targetPage != initialPagerPage)
        ) {
            notRecoveredProgress = 0f
            restorationApplied = true
            progressPagerState = pagerState
            return
        }
        if (hasRecoveredProgress && initialPagerPage != null && pagerState.settledPage != initialPagerPage) {
            notRecoveredProgress = 0f
            restorationApplied = true
            progressPagerState = pagerState
            return
        }
        val recovered = (if (hasRecoveredProgress) notRecoveredProgress else uiState.readingProgress)
            .coerceIn(0f, 1f)
        restorationJob?.cancel()
        restorationJob = coroutineScope.launch {
            if (expectedRecoveryGeneration != null && expectedRecoveryGeneration != recoveryGeneration) return@launch
            if (uiState.pagerState !== pagerState || pagerState.pageCount == 0) return@launch
            val target = ((pagerState.pageCount * recovered).roundToInt() - 1)
                .coerceIn(0, pagerState.pageCount - 1)
            if (expectedRecoveryGeneration != null && expectedRecoveryGeneration != recoveryGeneration) return@launch
            if (uiState.pagerState === pagerState) {
                pagerState.scrollToPage(target)
                if (expectedRecoveryGeneration != null && expectedRecoveryGeneration != recoveryGeneration) return@launch
                if (hasRecoveredProgress && notRecoveredProgress == recovered) {
                    notRecoveredProgress = 0f
                    restorationApplied = true
                }
                progressPagerState = pagerState
            }
        }
    }
}
