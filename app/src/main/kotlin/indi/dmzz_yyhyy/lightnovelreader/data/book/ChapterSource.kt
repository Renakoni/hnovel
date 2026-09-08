package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow

/** Chapter access for the reader, without navigation or whole-book work scheduling. */
interface ChapterSource {
    // Normal builds emit processed local data when present, then the remote result (including errors).
    // Flows are cold; benchmark builds stop after a local hit.
    fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ): Flow<Result<BookVolumes, WebRequestError>>

    fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ): Flow<Result<ChapterContent, WebRequestError>>

    /** Fetches and stores the raw remote chapter without applying display text processing. */
    suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    )
}
