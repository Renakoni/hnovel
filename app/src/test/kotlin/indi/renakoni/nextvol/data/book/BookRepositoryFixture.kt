package indi.renakoni.nextvol.data.book

import androidx.work.WorkManager
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.proxy.ProxyWebBookDataSource
import io.mockk.coEvery
import io.mockk.every
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.mockk.mockk

internal class BookRepositoryFixture {
    val local = mockk<LocalBookDataSource>()
    val remote = mockk<ProxyWebBookDataSource>()
    var activeRemote = remote
    private val runtime = mockk<SourceRuntime> {
        coEvery { getBookInformation(any(), any(), any()) } coAnswers { activeRemote.getBookInformation(firstArg(), secondArg()) }
        coEvery { getBookVolumes(any(), any(), any()) } coAnswers { activeRemote.getBookVolumes(firstArg(), secondArg()) }
        coEvery { getChapterContent(any(), any(), any(), any()) } coAnswers { activeRemote.getChapterContent(firstArg(), secondArg(), thirdArg()) }
    }
    val registry = mockk<WebSourceRegistry> {
        every { sources } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        coEvery { resolve(BookIdentity.book("book").sourceId) } returns SourceResolution.Ready(runtime)
    }
    val text = mockk<TextProcessingRepository>()
    val workManager by lazy { mockk<WorkManager>() }
    val bookshelves by lazy { mockk<BookshelfRepository>() }

    val downloads = mockk<BookDownloadStore>(relaxed = true)

    fun chapterRepository() = ChapterRepository(registry, local, text)

    fun repository() = BookRepository(
        local, bookshelves, text, workManager,
        chapterRepository(), BookReadingDataRepository(local), registry, downloads,
    )
}
