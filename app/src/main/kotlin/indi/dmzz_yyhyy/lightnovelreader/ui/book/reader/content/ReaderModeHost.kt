package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

enum class ReaderMode { Scroll, Flip }

/**
 * Owns the selected controller and binds it before the reader publishes its UI state.
 * Replacing or closing the host closes the previous controller and its owned tasks.
 */
internal class ReaderModeHost(
    private val createController: (ReaderMode) -> ReaderModeController,
) {
    private var selectedMode: ReaderMode? = null
    private var controller: ReaderModeController? = null
    private var transitionRequestedChapterId: String? = null
    val uiState: ContentUiState? get() = controller?.uiState

    fun select(mode: ReaderMode, currentBookId: () -> String, currentChapterId: () -> String): Boolean {
        if (selectedMode == mode) return false
        val previousController = controller
        transitionRequestedChapterId = controller?.requestedChapterId
        controller = createController(mode)
        selectedMode = mode
        try {
            previousController?.close()
            controller?.changeBookId(currentBookId())
            controller?.changeChapter(currentChapterId())
        } finally {
            transitionRequestedChapterId = null
        }
        return true
    }

    fun close() {
        controller?.close()
        controller = null
        selectedMode = null
    }

    fun changeBookId(id: String) = controller?.changeBookId(id)
    fun changeChapter(id: String) = controller?.changeChapter(id)

    val requestedChapterId: String?
        get() = transitionRequestedChapterId ?: controller?.requestedChapterId
    fun loadNextChapter() = controller?.loadNextChapter()
    fun loadPrevChapter() = controller?.loadPrevChapter()
}
