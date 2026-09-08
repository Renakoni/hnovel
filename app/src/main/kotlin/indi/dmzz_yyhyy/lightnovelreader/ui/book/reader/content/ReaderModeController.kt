package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

/**
 * Commands shared by reading modes. Implementations borrow the reader's coroutine scope;
 * selecting another mode does not end that scope or flush progress.
 */
interface ReaderModeController {
    val uiState: ContentUiState
    fun changeBookId(id: String)
    fun loadNextChapter()
    fun loadPrevChapter()
    fun changeChapter(id: String)
}
