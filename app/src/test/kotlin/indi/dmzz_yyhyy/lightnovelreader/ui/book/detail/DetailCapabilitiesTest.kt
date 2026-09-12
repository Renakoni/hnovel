package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.github.michaelbull.result.Ok
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DetailCapabilitiesTest {
    @Test fun metadataDetailsNeverRequestDirectoriesOrEnqueueReadingWorkAndOfflineDirectoriesRemainUsable() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = SourceBookId(ZLibrarySources.ID, "1/abcdef").storageKey
        val book = BookInformation(key, "Metadata book", author = "Author", description = "Description",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
        val availability = MutableStateFlow(BookReadingAvailability(false, false, true, 1))
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>()
        val downloads = mockk<DownloadProgressRepository>()
        val work = mockk<WorkManager>(relaxed = true)
        every { repository.readingAvailability(key) } returns availability
        every { repository.getBookInformationFlow(key, any()) } returns flowOf(Ok(book))
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.getBookVolumesFlow(key, any()) } returns flowOf(Ok(BookVolumes(key, emptyList())))
        coEvery { repository.getIsBookCached(key) } returns false
        coEvery { shelves.getBookshelfBookMetadata(key) } returns null
        every { shelves.getBookshelfBookMetadataFlow(key) } returns flowOf(null)
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        val model = DetailViewModel(repository, shelves, downloads, work)
        val store = ViewModelStore().apply { put("detail", model) }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
        try {
            model.init(key)
            until { model.uiState.bookInformation != null && model.uiState.metadataOnly }
            assertFalse(model.uiState.readingAvailable)
            assertNull(model.uiState.bookVolumes)
            assertNull(model.cacheBook(key).first())
            assertNull(model.exportToEpub(Uri.EMPTY, key, "Metadata book").first())
            verify(exactly = 0) { repository.getBookVolumesFlow(any<String>(), any()) }
            verify(exactly = 0) { repository.cacheBook(any()) }
            verify(exactly = 0) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
            model.retryInformation()
            until { model.uiState.bookInformation != null }
            verify(exactly = 2) { repository.getBookInformationFlow(key, any()) }
            availability.value = BookReadingAvailability(false, true, false, null)
            until { model.uiState.bookVolumes != null }
            assertTrue(model.uiState.readingAvailable)
            assertFalse(model.uiState.canCache)
            verify(exactly = 1) { repository.getBookVolumesFlow(key, any()) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
