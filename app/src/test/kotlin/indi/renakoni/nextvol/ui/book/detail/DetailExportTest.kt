package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DetailExportTest {
    @Test fun completionWaitsForUiConsumptionAndDoesNotReappearAfterSharing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<BookRepository> { every { downloadGeneration() } returns 7 }
        val infos = MutableStateFlow<List<WorkInfo>>(emptyList())
        val request = slot<OneTimeWorkRequest>()
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        val work = mockk<WorkManager> {
            every { enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, capture(request)) } returns operation
            every { getWorkInfosForUniqueWorkFlow(any()) } returns infos
        }
        val model = DetailViewModel(repository, mockk(), mockk(), work, mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        try {
            (model.uiState as MutableDetailUiState).readingAvailable = true
            model.startEpubExport(BookIdentity.bookKey("1"), "Book")
            runCurrent()
            assertNull(request.captured.workSpec.input.getString("uri"))
            val completed = mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED }
            infos.value = listOf(completed)
            runCurrent()
            assertSame(completed, model.exportResult)
            assertEquals(0, infos.subscriptionCount.value)
            model.clearExportResult()
            infos.value = emptyList()
            infos.value = listOf(completed)
            runCurrent()
            assertNull(model.exportResult)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
