package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderFontFamilySettings
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentUiState
import indi.renakoni.nextvol.ui.components.Loading
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Composable
fun ContentComponent(
    modifier: Modifier = Modifier,
    uiState: ContentUiState?,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    val selectionState = remember { ReaderSelectionState() }
    CompositionLocalProvider(LocalReaderSelectionState provides selectionState) {
        uiState.let { contentUiState ->
            when(contentUiState) {
            is FlipPageContentUiState -> FlipPageContentComponent(
                modifier,
                contentUiState,
                settingState,
                paddingValues,
                changeIsImmersive,
                onClickPrevChapter,
                onClickNextChapter,
                chapterTitle,
            )
            is ScrollContentUiState -> ScrollContentComponent(
                modifier,
                contentUiState,
                settingState,
                fontFamilySettings,
                paddingValues,
                changeIsImmersive,
                onClickPrevChapter,
                onClickNextChapter,
                chapterTitle,
            )
            }
        }
    }
}

@Composable
fun ChapterContentLoading() {
    Loading()
}

@Composable
fun ChapterContentError(
    error: WebRequestError,
    chapterTitle: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chapterTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(error.title, style = MaterialTheme.typography.titleSmall)
        Text(error.message)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
