package indi.dmzz_yyhyy.lightnovelreader.data.bookshelf

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class MetadataBookmarkTest {
    @Test fun autoCacheShelvesKeepMetadataBookmarksWithoutSchedulingImpossibleChapterDownloads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LightNovelReaderDatabase::class.java).allowMainThreadQueries().build()
        val registry = WebSourceRegistry()
        val work = mockk<WorkManager>(relaxed = true)
        val shelves = BookshelfRepository(db.bookshelfDao(), work, registry)
        val metadata = SourceBookId(ZLibrarySources.ID, "1/abcdef")
        val novel = SourceBookId(Identifier("fixture", "novel"), "book")
        fun provider(book: SourceBookId) = object : WebBookDataSource by EmptyWebDataSource { override val id = book.sourceId }
        registry.register(provider(metadata), ZLibrarySources.METADATA)
        registry.register(provider(novel), SourceMetadata(WebDataSourceItem(novel.sourceId, "Novel", "fixture"),
            setOf(SourceCapability.Directory, SourceCapability.ChapterContent)))
        val info = BookInformation(metadata.storageKey, "Saved metadata", author = "Author", description = "",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
        try {
            shelves.addBookshelf(Bookshelf(id = 1, name = "Automatic cache", autoCache = true,
                allBookIds = listOf(metadata.storageKey, novel.storageKey)))
            shelves.addBookIntoBookShelf(1, info)
            assertTrue(shelves.getBookshelf(1)!!.allBookIds.contains(metadata.storageKey))
            verify(exactly = 0) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
            shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
            verify(exactly = 1) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
        } finally { registry.unregister(metadata.sourceId); registry.unregister(novel.sourceId); db.close() }
    }
}
