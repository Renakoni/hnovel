package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import io.nightfish.lightnovelreader.api.error.WebRequestError

enum class ChapterEntry { Restore, Start, End }

/** A boundary request is separate from the chapter whose pages are still visible. */
class FlipChapterTransition(
    val chapterId: String,
    val entry: ChapterEntry,
    val result: Result<ChapterContentUiState, WebRequestError>? = null,
)

interface FlipPageContentUiState: ContentUiState {
    val updatePageState: (PagerState) -> Unit
    val updateAnchoredPageState: (PagerState) -> Unit get() = updatePageState
    val updateSpeechPageState: (PagerState) -> Unit get() = updateAnchoredPageState
    val pagerState: PagerState
    val pendingChapter: FlipChapterTransition? get() = null
    val commitPendingChapter: (FlipChapterTransition, PagerState) -> Boolean get() = { _, _ -> false }
    val failPendingChapter: (FlipChapterTransition, WebRequestError) -> Unit get() = { _, _ -> }
    val retryPendingChapter: () -> Unit get() = {}
    val cancelPendingChapter: () -> Unit get() = {}
}

class MutableFlipPageContentUiState(
    override val loadNextChapter: () -> Unit,
    override val loadPrevChapter: () -> Unit,
    override val changeChapter: (String) -> Unit,
    override val updatePageState: (PagerState) -> Unit,
    override val updateAnchoredPageState: (PagerState) -> Unit = updatePageState,
    override val updateSpeechPageState: (PagerState) -> Unit = updateAnchoredPageState,
    override val commitPendingChapter: (FlipChapterTransition, PagerState) -> Boolean = { _, _ -> false },
    override val failPendingChapter: (FlipChapterTransition, WebRequestError) -> Unit = { _, _ -> },
    override val retryPendingChapter: () -> Unit = {},
    override val cancelPendingChapter: () -> Unit = {},
): FlipPageContentUiState {
    override var pendingChapter by mutableStateOf<FlipChapterTransition?>(null)
    override var pagerState by mutableStateOf(PagerState { 0 })
    override var bookId by mutableStateOf("")
    override var readingChapterId: String? by mutableStateOf(null)
    override var readingChapterContent: Result<ChapterContentUiState, WebRequestError>? by mutableStateOf(null)
    override var readingProgress by mutableFloatStateOf(0f)
}
