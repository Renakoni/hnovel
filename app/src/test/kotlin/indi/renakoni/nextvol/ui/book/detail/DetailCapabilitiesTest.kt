package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getError
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
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
        every { repository.downloadChanges(key) } returns flowOf(Unit)
        coEvery { repository.downloadState(key, any()) } returns indi.renakoni.nextvol.data.download.BookDownloadState()
        every { work.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        coEvery { shelves.getBookshelfBookMetadata(key) } returns null
        every { shelves.getBookshelfBookMetadataFlow(key) } returns flowOf(null)
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        val model = DetailViewModel(repository, shelves, downloads, work, mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
        try {
            model.init(key)
            until { model.uiState.bookInformation != null && model.uiState.metadataOnly }
            assertFalse(model.uiState.readingAvailable)
            assertNull(model.uiState.bookVolumes)
            assertNull(model.cacheBook(key).first())
            assertNull(model.exportToEpub(key, "Metadata book").first())
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

            val failure = io.nightfish.lightnovelreader.api.error.WebRequestError("Directory", "No chapters")
            every { repository.getBookVolumesFlow(key, any()) } returns flowOf(Err(failure))
            model.retryVolumes()
            until { model.uiState.bookVolumes?.getError() == failure }
            val cancelled = CompletableDeferred<Unit>()
            every { repository.getBookVolumesFlow(key, any()) } returns flow {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            model.retryVolumes()
            until { model.uiState.bookVolumes == null }
            verify(timeout = 5000, exactly = 3) { repository.getBookVolumesFlow(key, any()) }
            availability.value = BookReadingAvailability(false, false, true, 2)
            withTimeout(5000) { cancelled.await() }
            until { !model.uiState.readingAvailable }
            model.retryVolumes()
            assertNull(model.uiState.bookVolumes)
            verify(exactly = 3) { repository.getBookVolumesFlow(key, any()) }
            verify(exactly = 2) { repository.getBookInformationFlow(key, any()) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }
}
