package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow

/**
 * Chapter access for the reader, without navigation or whole-book work scheduling.
 *
 * Both flows are cold: each collection emits processed local data when present, then processed
 * remote data on success. A remote error is emitted only if that collection had no local data;
 * a failed refresh leaves the cached success as the last emission. Benchmark builds stop at a
 * local hit. Exceptions from storage or text processing, and coroutine cancellation, propagate.
 */
interface ChapterSource {
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
