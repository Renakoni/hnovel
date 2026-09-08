package indi.dmzz_yyhyy.lightnovelreader.data.book

import androidx.work.WorkManager
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.mockk.every
import io.mockk.mockk

internal class BookRepositoryFixture {
    val local = mockk<LocalBookDataSource>()
    val remote = mockk<ProxyWebBookDataSource>()
    var activeRemote = remote
    val provider = mockk<WebBookDataSourceProvider> {
        every { value } answers { activeRemote }
    }
    val text = mockk<TextProcessingRepository>()
    val workManager by lazy { mockk<WorkManager>() }
    val bookshelves by lazy { mockk<BookshelfRepository>() }

    fun chapterRepository() = ChapterRepository(provider, local, text)

    fun repository() = BookRepository(
        provider, local, bookshelves, text, workManager,
        chapterRepository(), BookReadingDataRepository(local),
    )
}
