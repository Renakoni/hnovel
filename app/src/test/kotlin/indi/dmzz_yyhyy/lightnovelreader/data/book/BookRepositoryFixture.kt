package indi.dmzz_yyhyy.lightnovelreader.data.book

import androidx.work.WorkManager
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.mockk.coEvery
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.mockk.mockk

internal class BookRepositoryFixture {
    val local = mockk<LocalBookDataSource>()
    val remote = mockk<ProxyWebBookDataSource>()
    var activeRemote = remote
    private val runtime = mockk<SourceRuntime> {
        coEvery { getBookInformation(any(), any()) } coAnswers { activeRemote.getBookInformation(firstArg(), secondArg()) }
        coEvery { getBookVolumes(any(), any()) } coAnswers { activeRemote.getBookVolumes(firstArg(), secondArg()) }
        coEvery { getChapterContent(any(), any(), any()) } coAnswers { activeRemote.getChapterContent(firstArg(), secondArg(), thirdArg()) }
    }
    val registry = mockk<WebSourceRegistry> {
        coEvery { resolve(BookIdentity.book("book").sourceId) } returns SourceResolution.Ready(runtime)
    }
    val text = mockk<TextProcessingRepository>()
    val workManager by lazy { mockk<WorkManager>() }
    val bookshelves by lazy { mockk<BookshelfRepository>() }

    fun chapterRepository() = ChapterRepository(registry, local, text)

    fun repository() = BookRepository(
        local, bookshelves, text, workManager,
        chapterRepository(), BookReadingDataRepository(local), registry,
    )
}
