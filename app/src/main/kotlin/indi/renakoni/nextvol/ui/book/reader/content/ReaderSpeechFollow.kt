package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import indi.renakoni.nextvol.tts.SpeechPosition
import indi.renakoni.nextvol.tts.SpeechTextIndex
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor

internal data class ReaderSpeechFollow(
    val position: SpeechPosition? = null,
    val following: Boolean = false,
    val onManualNavigation: () -> Unit = {},
    val active: Boolean = true,
) {
    fun ranges(chapter: ChapterContentUiState): List<SpeechTextIndex.Range> =
        position?.takeIf { it.chapterId == chapter.id }?.let(chapter.speechTextIndex::ranges).orEmpty()

    fun anchor(chapter: ChapterContentUiState): ReaderContentAnchor? =
        if (!following || position?.chapterId != chapter.id) null else chapter.speechTextIndex.ranges(
            position.copy(start = position.anchor, end = position.anchor + 1)
        ).firstOrNull()?.let { ReaderContentAnchor(it.componentIndex, it.start) }
}

internal val LocalReaderSpeechFollow = compositionLocalOf { ReaderSpeechFollow() }
internal val LocalReaderSpeechRanges = compositionLocalOf { emptyList<SpeechTextIndex.Range>() }

/** User input detaches before the scroll is dispatched; programmatic following never detaches. */
@Composable
internal fun Modifier.readerSpeechManualScroll(): Modifier {
    val speech by rememberUpdatedState(LocalReaderSpeechFollow.current)
    return nestedScroll(remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero) speech.onManualNavigation()
                return Offset.Zero
            }
        }
    })
}
