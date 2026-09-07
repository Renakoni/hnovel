package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderFontFamilySettings
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderSettings
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Loading
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
    onClickNextChapter: () -> Unit
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
                onClickNextChapter
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
    error: WebRequestError
) {
    //TODO 错误显示
}
