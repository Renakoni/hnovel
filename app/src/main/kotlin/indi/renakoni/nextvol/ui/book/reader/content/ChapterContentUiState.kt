package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.runtime.Stable
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.tts.SpeechTextIndex
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent

@Stable
class ChapterContentUiState(
    val id: String,
    val title: String,
    val content: List<AbstractContentComponent<*>>,
    val prevChapter: String?,
    val nextChapter: String?
) {
    internal val speechTextIndex by lazy {
        SpeechTextIndex(content.mapIndexedNotNull { index, component ->
            (component as? SimpleTextComponent)?.let { index to it.data.text }
        })
    }

    fun hasPrevChapter(): Boolean = prevChapter != null

    fun hasNextChapter(): Boolean = nextChapter != null
}
