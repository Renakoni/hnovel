package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

/**
 * Commands shared by reading modes. Implementations borrow the reader's coroutine scope;
 * selecting another mode does not end that scope or flush progress.
 */
interface ReaderModeController {
    /** Most recent chapter selected by this controller, including internal next/previous moves. */
    val requestedChapterId: String?
        get() = null

    val uiState: ContentUiState
    fun changeBookId(id: String)
    fun loadNextChapter()
    fun loadPrevChapter()
    fun changeChapter(id: String)
}
