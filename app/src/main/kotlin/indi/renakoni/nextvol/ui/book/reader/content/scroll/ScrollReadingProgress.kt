package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.runtime.snapshotFlow
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.utils.throttleLatest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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

    fun start() {
        coroutineScope.launch(mainDispatcher) {
            snapshotFlow { measuredPosition() }
                .filterNotNull()
                .throttleLatest(120L, currentTimeMillis)
                .collect { position ->
                    val (chapterId, offset, size) = position
                    val newProgress = calculateReadingProgress(offset, size)
                    if (newProgress == uiState.readingProgress) return@collect
                    uiState.readingProgress = newProgress

                    val now = currentTimeMillis()
                    val scrolling = uiState.lazyListState.isScrollInProgress

                    if (scrolling && now - lastWriteReadingProgress < 2500 && newProgress < 1f) return@collect
                    lastWriteReadingProgress = now

                    updateReadingProgress(chapterId, newProgress)
                }
        }

        coroutineScope.launch(mainDispatcher) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling) {
                        val (chapterId, offset, size) = measuredPosition() ?: return@collect
                        val finalProgress = calculateReadingProgress(offset, size)

                        if (uiState.readingProgress != finalProgress) {
                            uiState.readingProgress = finalProgress
                        }
                        updateReadingProgress(chapterId, finalProgress)
                        lastWriteReadingProgress = currentTimeMillis()
                    }
                }
        }
    }

    fun writeProgressRightNow() {
        if (uiState.isRestoringProgress || uiState.readingChapterContent?.get() == null) return
        updateReadingProgress(uiState.readingChapterId ?: return, uiState.readingProgress)
    }

    private fun measuredPosition(): Triple<String, Int, Int>? {
        val chapterId = uiState.readingChapterId ?: return null
        if (uiState.isRestoringProgress || uiState.readingChapterContent?.get() == null || viewportHeight() <= 0) return null
        val item = uiState.lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == chapterId && it.contentType == true } ?: return null
        return Triple(chapterId, item.offset, item.size)
    }

    private fun calculateReadingProgress(itemOffset: Int, itemSize: Int): Float =
        ((-itemOffset + viewportHeight()).toFloat() / itemSize.coerceAtLeast(1)).coerceIn(0f, 1f)
}
