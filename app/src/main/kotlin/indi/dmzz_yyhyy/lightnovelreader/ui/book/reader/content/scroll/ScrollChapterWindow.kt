package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Owns the three chapter slots and their subscriptions, including continuous-scroll transitions. */
internal class ScrollChapterWindow(
    private val uiState: MutableScrollContentUiSate,
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
    private val settings: ContinuousScrollSettings,
    private val coroutineScope: CoroutineScope,
    private val viewportHeight: () -> Int,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var progressScrollLoadJob: Job? = null
    private var collectPrevChapterJob: Job? = null
    private var collectCurrentChapterJob: Job? = null
    private var collectNextChapterJob: Job? = null
    private var continuousObservationGeneration = 0L
    private val observationLock = Any()

    fun startContinuousObservation() {
        val observationGeneration = synchronized(observationLock) {
            continuousObservationGeneration++
            continuousObservationGeneration
        }
        progressScrollLoadJob?.cancel()
        progressScrollLoadJob = coroutineScope.launch {
            snapshotFlow { uiState.lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0) }.collect { itemInfo ->
                uiState.readingChapterContent?.onOk { readingChapterContent ->
                    if (
                        itemInfo != null &&
                        itemInfo.key == readingChapterContent.prevChapter &&
                        viewportHeight() != 0 &&
                        itemInfo.offset <= -viewportHeight() &&
                        readingChapterContent.hasPrevChapter()
                    ) {
                        var displacedChapter: ChapterContentUiState? = null
                        withCurrentObservation(observationGeneration) {
                            collectNextChapterJob?.cancel()
                            collectCurrentChapterJob?.cancel()
                            collectPrevChapterJob?.cancel()
                            val nextChapter = uiState.contentList[1]
                            val currentChapter = uiState.contentList[0]
                            val currentChapterId = readingChapterContent.prevChapter
                            displacedChapter = currentChapter?.second?.get()
                            resetContentList()
                            uiState.contentList[2] = nextChapter
                            uiState.contentList[1] = currentChapter
                            collectNextChapterJob = collectChapter(2, readingChapterContent.id)
                            collectCurrentChapterJob = collectChapter(1, currentChapterId) { chapterContent ->
                                withCurrentObservation(observationGeneration) {
                                    collectPrevChapterJob?.cancel()
                                    collectPrevChapterJob = collectAdjacentChapter(
                                        index = 0,
                                        chapterId = chapterContent.prevChapter,
                                        currentChapterId = chapterContent.id,
                                        occupiedChapterIds = setOf(readingChapterContent.id)
                                    )
                                }
                                updateLastReadChapter(chapterContent.id, chapterContent.title)
                            }
                            uiState.readingChapterId = currentChapterId
                        }
                        displacedChapter?.let { updateLastReadChapter(it.id, it.title) }
                    }
                    if (
                        itemInfo != null &&
                        itemInfo.key == readingChapterContent.nextChapter &&
                        readingChapterContent.hasNextChapter()
                    ) {
                        var displacedChapter: ChapterContentUiState? = null
                        withCurrentObservation(observationGeneration) {
                            collectNextChapterJob?.cancel()
                            collectCurrentChapterJob?.cancel()
                            collectPrevChapterJob?.cancel()
                            val prevChapter = uiState.contentList[1]
                            val currentChapter = uiState.contentList[2]
                            val currentChapterId = readingChapterContent.nextChapter
                            displacedChapter = currentChapter?.second?.get()
                            resetContentList()
                            uiState.contentList[0] = prevChapter
                            uiState.contentList[1] = currentChapter
                            collectPrevChapterJob = collectChapter(0, readingChapterContent.id)
                            collectCurrentChapterJob = collectChapter(1, currentChapterId) { chapterContent ->
                                withCurrentObservation(observationGeneration) {
                                    collectNextChapterJob?.cancel()
                                    collectNextChapterJob = collectAdjacentChapter(
                                        index = 2,
                                        chapterId = chapterContent.nextChapter,
                                        currentChapterId = chapterContent.id,
                                        occupiedChapterIds = setOf(readingChapterContent.id)
                                    )
                                }
                                updateLastReadChapter(chapterContent.id, chapterContent.title)
                            }
                            uiState.readingChapterId = currentChapterId
                        }
                        displacedChapter?.let { updateLastReadChapter(it.id, it.title) }
                    }
                }
            }
        }
    }

    fun stopContinuousObservation() {
        synchronized(observationLock) {
            continuousObservationGeneration++
            progressScrollLoadJob?.cancel()
            collectPrevChapterJob?.cancel()
            collectPrevChapterJob = null
            collectNextChapterJob?.cancel()
            collectNextChapterJob = null
        }
    }

    private fun resetContentList() {
        uiState.contentList.clear()
        uiState.contentList.add(null)
        uiState.contentList.add(null)
        uiState.contentList.add(null)
    }

    fun changeChapter(id: String) {
        resetContentList()
        uiState.readingChapterId = id
        uiState.readingProgress = 0f
        uiState.lazyListState = LazyListState()
        coroutineScope.launch (ioDispatcher) {
            val isUsingContinuousScrolling = settings.isEnabled()
            collectRequestedChapter(id, isUsingContinuousScrolling)
        }
    }

    private fun collectRequestedChapter(id: String, continuousScrolling: Boolean) {
        collectCurrentChapterJob?.cancel()
        val observationGeneration = continuousObservationGeneration
        collectCurrentChapterJob = coroutineScope.launch(ioDispatcher) {
            chapters.load(id, uiState.bookId).collect { result ->
                uiState.contentList[1] = id to result
                result.onOk { chapterContent ->
                    readingData.updateUserReadingData(uiState.bookId) { userReadingData ->
                        uiState.readingProgress = userReadingData.currentChapterReadingProgressMap[id] ?: 0f
                        userReadingData.copy(
                            lastReadTime = LocalDateTime.now(),
                            lastReadChapterId = id,
                            lastReadChapterTitle = chapterContent.title,
                        )
                    }
                    chapterContent.nextChapter?.let {
                        chapters.preload(
                            it,
                            uiState.bookId
                        )
                    }

                    if (continuousScrolling) {
                        withCurrentObservation(observationGeneration) {
                            collectPrevChapterJob?.cancel()
                            collectPrevChapterJob = collectAdjacentChapter(
                                index = 0,
                                chapterId = chapterContent.prevChapter,
                                currentChapterId = chapterContent.id,
                                occupiedChapterIds = setOfNotNull(chapterContent.nextChapter)
                            )
                            collectNextChapterJob?.cancel()
                            collectNextChapterJob = collectAdjacentChapter(
                                index = 2,
                                chapterId = chapterContent.nextChapter,
                                currentChapterId = chapterContent.id,
                                occupiedChapterIds = setOfNotNull(chapterContent.prevChapter)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun collectChapter(
        index: Int,
        chapterId: String,
        onLoaded: suspend (ChapterContentUiState) -> Unit = {}
    ) = coroutineScope.launch {
        chapters.load(chapterId, uiState.bookId).collect { content ->
            uiState.contentList[index] = chapterId to content
            content.onOk { onLoaded(it) }
        }
    }

    private fun collectAdjacentChapter(
        index: Int,
        chapterId: String?,
        currentChapterId: String,
        occupiedChapterIds: Set<String> = emptySet()
    ): Job? {
        val adjacentChapterId = chapterId
            ?.takeIf { it != currentChapterId }
            ?.takeIf { it !in occupiedChapterIds }
            ?: run {
                uiState.contentList[index] = null
                return null
            }
        return collectChapter(index, adjacentChapterId)
    }

    private inline fun withCurrentObservation(generation: Long, block: () -> Unit): Boolean =
        synchronized(observationLock) {
            if (generation != continuousObservationGeneration) return false
            block()
            true
        }

    private suspend fun updateLastReadChapter(chapterId: String, chapterTitle: String?) {
        readingData.updateUserReadingData(uiState.bookId) {
            it.copy(
                lastReadTime = LocalDateTime.now(),
                lastReadChapterId = chapterId,
                lastReadChapterTitle = chapterTitle ?: it.lastReadChapterTitle
            )
        }
    }
}
