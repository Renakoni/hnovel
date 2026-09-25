package indi.renakoni.nextvol.data.book

import com.github.michaelbull.result.coroutines.coroutineBinding
import hnovel.content.LoginRefreshTarget
import indi.renakoni.nextvol.data.web.SourceRuntime
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import javax.inject.Inject

internal data class ReadingPanelUpdate(val volumes: BookVolumes? = null, val contentChanged: Boolean = false)

/** No shelf membership or remote writes. Only successful, declared refreshes reach the reader. */
internal class ReadingPanelRefresh @Inject constructor(
    private val books: BookRepository, private val chapters: ChapterRepository,
    private val text: indi.renakoni.nextvol.data.text.TextProcessingRepository,
) {
    suspend fun refresh(book: SourceBookId, chapter: SourceChapterId?, runtime: SourceRuntime,
        targets: Set<LoginRefreshTarget>) = coroutineBinding {
        require(runtime.id == book.sourceId && (chapter == null || chapter.book == book))
        if (LoginRefreshTarget.BookInformation in targets)
            books.refreshBookInformation(book, fresh = true, expectedRuntime = runtime).bind()
        val volumes = if (LoginRefreshTarget.Directory in targets)
            chapters.refreshBookVolumes(book, WebDataSourcePriority.High, fresh = true, expectedRuntime = runtime).bind() else null
        val content = chapter != null && LoginRefreshTarget.Content in targets
        if (content) chapters.refreshChapter(chapter!!, WebDataSourcePriority.High, fresh = true, expectedRuntime = runtime).bind()
        ReadingPanelUpdate(volumes?.let { text.processBookVolumes { it } }, content)
    }
}
