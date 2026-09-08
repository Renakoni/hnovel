package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Shared chapter mapping; the caller retains subscription, dispatcher and preload ownership. */
@Singleton
class ReaderChapterLoader @Inject constructor(
    private val chapterSource: ChapterSource,
    private val contentRenderer: ContentRenderer,
) {
    fun load(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ): Flow<Result<ChapterContentUiState, WebRequestError>> =
        chapterSource.getChapterContentFlow(chapterId, bookId, priority).map { result ->
            result.map {
                ChapterContentUiState(
                    id = it.id,
                    title = it.title,
                    content = contentRenderer.getContentDataFromJson(it.content).components,
                    prevChapter = it.prevChapter,
                    nextChapter = it.nextChapter,
                )
            }
        }

    suspend fun preload(chapterId: String, bookId: String) =
        chapterSource.preloadChapterContent(chapterId, bookId)
}
