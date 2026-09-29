package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.utils.throttleLatest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Owns scroll progress observation and write throttling. ON_STOP still uses the explicit callback. */
internal class ScrollReadingProgress(
    private val uiState: MutableScrollContentUiSate,
    private val coroutineScope: CoroutineScope,
    private val updateReadingProgress: (String, Float) -> Unit,
    private val viewportHeight: () -> Int,
    private val mainDispatcher: CoroutineDispatcher,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastWriteReadingProgress = 0L
    private var lastSavedPosition: Triple<String, String, Float>? = null
    private var lastSavedList: LazyListState? = null
    private var revision by mutableLongStateOf(0L)
    private var restoredList: LazyListState? = null
    private var pendingPosition: Position? = null

    private data class Position(
        val book: String, val chapterId: String, val chapter: ChapterContentUiState, val list: LazyListState,
        val revision: Long, val offset: Int, val size: Int, val height: Int,
    ) {
        val progress: Float get() = ((-offset.toFloat() + height) / size).coerceIn(0f, 1f)
    }

    fun restoring(list: LazyListState) {
        if (uiState.lazyListState !== list) return
        flushPending()
        if (restoredList !== list || !uiState.isRestoringProgress) revision++
        restoredList = list
        uiState.isRestoringProgress = true
    }

    fun restored(list: LazyListState) {
        if (uiState.lazyListState === list) uiState.isRestoringProgress = false
        // Placement completing is not a reading event. Keep the write barrier.
    }

    fun readingPositioned(list: LazyListState) {
        if (uiState.lazyListState !== list || uiState.isRestoringProgress) return
        if (restoredList === list) {
            restoredList = null
            revision++
        }
    }

    fun start() {
        coroutineScope.launch(mainDispatcher) {
            snapshotFlow { if (uiState.lazyListState.isScrollInProgress) measuredPosition() else null }
                .filterNotNull()
                .onEach(::rememberReading)
                .throttleLatest(120L, currentTimeMillis)
                .collect { position ->
                    if (!isCurrent(position)) return@collect
                    val newProgress = position.progress
                    if (newProgress == uiState.readingProgress) return@collect
                    uiState.readingProgress = newProgress

                    val now = currentTimeMillis()
                    if (now - lastWriteReadingProgress < 2500 && newProgress < 1f) return@collect
                    savePosition(position)
                }
        }

        coroutineScope.launch(mainDispatcher) {
            // A restored/promoted chapter can be measured after scrolling has already stopped.
            snapshotFlow { if (uiState.lazyListState.isScrollInProgress) null else measuredPosition() }
                .filterNotNull()
                .collect { position ->
                    if (!isCurrent(position)) return@collect
                    uiState.readingProgress = position.progress
                    savePosition(position)
                }
        }
    }

    fun writeProgressRightNow() {
        measuredPosition()?.let { position ->
            uiState.readingProgress = position.progress
            savePosition(position, force = true)
        }
        flushPending(force = true)
    }

    private fun rememberReading(sample: Position) {
        if (isCurrent(sample) && restoredList !== sample.list) pendingPosition = sample
    }

    private fun savePosition(sample: Position, force: Boolean = false) {
        if (!isCurrent(sample) || restoredList === sample.list) return
        pendingPosition = sample
        flushPending(force)
    }

    private fun flushPending(force: Boolean = false) {
        val sample = pendingPosition ?: return
        pendingPosition = null
        // This was authorized before the reflow. Do not sample its replacement geometry.
        if (sample.book != uiState.bookId || sample.chapterId != uiState.readingChapterId ||
            sample.chapter !== uiState.readingChapterContent?.get()) return
        val position = Triple(sample.book, sample.chapterId, sample.progress)
        if (!force && position == lastSavedPosition && lastSavedList === sample.list) return
        updateReadingProgress(sample.chapterId, sample.progress)
        lastSavedPosition = position
        lastSavedList = sample.list
        lastWriteReadingProgress = currentTimeMillis()
    }

    private fun isCurrent(position: Position): Boolean =
        position.revision == revision && position.book == uiState.bookId &&
            position.list === uiState.lazyListState && !uiState.isRestoringProgress &&
            position.chapterId == uiState.readingChapterId &&
            position.chapter === uiState.readingChapterContent?.get() && position.height == viewportHeight()

    private fun measuredPosition(): Position? {
        val chapterId = uiState.readingChapterId ?: return null
        val chapter = uiState.readingChapterContent?.get() ?: return null
        val height = viewportHeight()
        if (uiState.isRestoringProgress || height <= 0) return null
        val list = uiState.lazyListState
        val item = list.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == chapterId && it.contentType == true } ?: return null
        if (item.size <= 0) return null
        return Position(uiState.bookId, chapterId, chapter, list, revision, item.offset, item.size, height)
    }
}
