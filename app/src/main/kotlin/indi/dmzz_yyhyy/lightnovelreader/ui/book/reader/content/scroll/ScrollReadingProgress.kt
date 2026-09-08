package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.runtime.snapshotFlow
import indi.dmzz_yyhyy.lightnovelreader.utils.throttleLatest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Owns scroll progress observation and write throttling. ON_STOP still uses the explicit callback. */
internal class ScrollReadingProgress(
    private val uiState: MutableScrollContentUiSate,
    private val coroutineScope: CoroutineScope,
    private val updateReadingProgress: (String, Float) -> Unit,
    private val viewportHeight: () -> Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastWriteReadingProgress = 0L

    fun start() {
        coroutineScope.launch(mainDispatcher) {
            snapshotFlow { uiState.lazyListState.firstVisibleItemScrollOffset }
                .throttleLatest(120L, currentTimeMillis)
                .collect {
                    val layoutInfo = uiState.lazyListState.layoutInfo
                    val chapterId = uiState.readingChapterId ?: return@collect
                    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId } ?: return@collect

                    val newProgress = calculateReadingProgress(item.offset, item.size)
                    if (newProgress == uiState.readingProgress) return@collect
                    uiState.readingProgress = newProgress

                    val now = currentTimeMillis()
                    val scrolling = uiState.lazyListState.isScrollInProgress

                    if (scrolling && now - lastWriteReadingProgress < 2500 && newProgress < 1f) return@collect
                    lastWriteReadingProgress = now

                    coroutineScope.launch(ioDispatcher) { updateReadingProgress(chapterId, newProgress) }
                }
        }

        coroutineScope.launch(mainDispatcher) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling) {
                        val layoutInfo = uiState.lazyListState.layoutInfo
                        val chapterId = uiState.readingChapterId ?: return@collect
                        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId } ?: return@collect

                        val finalProgress = calculateReadingProgress(item.offset, item.size)

                        if (uiState.readingProgress != finalProgress) {
                            uiState.readingProgress = finalProgress
                        }
                        coroutineScope.launch(ioDispatcher) { updateReadingProgress(chapterId, uiState.readingProgress) }
                        lastWriteReadingProgress = currentTimeMillis()
                    }
                }
        }
    }

    fun writeProgressRightNow() {
        updateReadingProgress(uiState.readingChapterId ?: return, uiState.readingProgress)
    }

    private fun calculateReadingProgress(itemOffset: Int, itemSize: Int): Float =
        ((-itemOffset + viewportHeight()).toFloat() / itemSize.coerceAtLeast(1)).coerceIn(0f, 1f)
}
