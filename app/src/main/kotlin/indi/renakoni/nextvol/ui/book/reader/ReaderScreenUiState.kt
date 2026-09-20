package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Stable
interface ReaderScreenUiState {
    val bookId: String?
    val userReadingData: UserReadingData?
    val bookVolumes: Result<BookVolumes, WebRequestError>?
    val contentUiState: ContentUiState?
}

class MutableReaderScreenUiState(
    contentUiState: ContentUiState?
): ReaderScreenUiState {
    override var bookId: String? by mutableStateOf(null)
    override var userReadingData: UserReadingData? by mutableStateOf(null)
    override var bookVolumes: Result<BookVolumes, WebRequestError>? by mutableStateOf(null)
    override var contentUiState by mutableStateOf(contentUiState)
}

internal fun ReaderScreenUiState.chapterTitle(chapterId: String?): String? {
    if (chapterId == null) return null
    val content = contentUiState
    if (content?.readingChapterId == chapterId) {
        content.readingChapterContent?.get()?.title?.let { return it }
    }
    return bookVolumes?.get()?.volumes?.asSequence()
        ?.flatMap { it.chapters.asSequence() }?.firstOrNull { it.id == chapterId }?.title
}
