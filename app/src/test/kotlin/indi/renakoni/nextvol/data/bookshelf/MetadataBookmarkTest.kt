package indi.renakoni.nextvol.data.bookshelf

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.mockk.mockk
import io.mockk.every
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
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java).allowMainThreadQueries().build()
        val registry = WebSourceRegistry()
        val work = mockk<WorkManager>(relaxed = true)
        val aliases = indi.renakoni.nextvol.data.book.BookAliasStore(db)
        val downloads = indi.renakoni.nextvol.data.download.BookDownloadStore(RuntimeEnvironment.getApplication(), db,
            indi.renakoni.nextvol.data.content.ContentJsonDecoder(indi.renakoni.nextvol.data.content.ContentComponentRegistry()))
        val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, work, aliases)
        val shelves = BookshelfRepository(db.bookshelfDao(), scheduler, registry, aliases)
        val enqueued = kotlinx.coroutines.CompletableDeferred<OneTimeWorkRequest>()
        every { work.getWorkInfosForUniqueWorkFlow(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } answers {
            enqueued.complete(thirdArg())
            mockk<androidx.work.Operation> { every { result } returns
                com.google.common.util.concurrent.Futures.immediateFuture(androidx.work.Operation.SUCCESS) }
        }
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
            val submitted = kotlinx.coroutines.withTimeout(5000) { enqueued.await() }
            assertEquals(submitted.id.toString(), downloads.entry(novel)!!.taskWorkId)
            assertEquals("Queued", downloads.entry(novel)!!.taskStatus)
            assertTrue(submitted.workSpec.input.getBoolean("persistedTask", false))
            assertEquals(androidx.work.NetworkType.CONNECTED, submitted.workSpec.constraints.requiredNetworkType)
            verify(exactly = 1) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
        } finally { registry.unregister(metadata.sourceId); registry.unregister(novel.sourceId); db.close() }
    }
}
