package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipReaderController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ContinuousScrollSettings
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollReaderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/** The construction boundary is the only controller code that knows both mode implementations. */
class ReaderModeFactory @Inject constructor(
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
) {
    fun create(
        mode: ReaderMode,
        readerScope: CoroutineScope,
        continuousScrollSettings: ContinuousScrollSettings,
        updateReadingProgress: (String, Float) -> Unit,
    ): ReaderModeController {
        val modeScope = CoroutineScope(
            readerScope.coroutineContext + SupervisorJob(readerScope.coroutineContext[Job])
        )
        val controller = when (mode) {
        ReaderMode.Flip -> FlipReaderController(
            chapters, readingData, modeScope, updateReadingProgress,
        )
        ReaderMode.Scroll -> ScrollReaderController(
            chapters, readingData, modeScope, continuousScrollSettings, updateReadingProgress,
        )
        }
        return object : ReaderModeController by controller {
            override fun close() = modeScope.cancel()
        }
    }
}
