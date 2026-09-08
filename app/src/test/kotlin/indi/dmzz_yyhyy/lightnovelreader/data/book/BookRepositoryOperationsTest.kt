package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.app.Application
import android.net.Uri
import androidx.concurrent.futures.ResolvableFuture
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import indi.dmzz_yyhyy.lightnovelreader.data.work.ExportBookToEPUBWork
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.DetailViewModel
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryOperationsTest {
    private val fixture = BookRepositoryFixture()

    @Test
    fun terminalWorkSelectionUsesTheExplicitSubmissionTagInsteadOfListOrder() {
        val older = mockk<WorkInfo>()
        every { older.state } returns WorkInfo.State.SUCCEEDED
        every { older.tags } returns setOf("lightnovelreader:work-submission:100")
        every { older.generation } returns 0
        every { older.runAttemptCount } returns 0
        every { older.id } returns UUID.fromString("00000000-0000-0000-0000-000000000001")

        val newer = mockk<WorkInfo>()
        every { newer.state } returns WorkInfo.State.FAILED
        every { newer.tags } returns setOf("lightnovelreader:work-submission:200")
        every { newer.generation } returns 0
        every { newer.runAttemptCount } returns 0
        every { newer.id } returns UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertSame(newer, selectLatestWorkInfo(listOf(newer, older)))
        assertSame(newer, selectLatestWorkInfo(listOf(older, newer)))
    }

    @Test
    fun cacheWorkKeepsItsWorkerInputAndObservesTheUniqueWorkIdentity() = runTest {
        val submitted = slot<OneTimeWorkRequest>()
        val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation> { every { result } returns completion }
        every { fixture.workManager.enqueueUniqueWork("cache:book", ExistingWorkPolicy.KEEP, capture(submitted)) } returns operation
        val repository = fixture.repository()
        val observed = repository.cacheBook("book")
        val work = submitted.captured
        assertEquals(CacheBookWork::class.java.name, work.workSpec.workerClassName)
        assertEquals(mapOf("bookId" to "book"), work.workSpec.input.keyValueMap)
        verify(exactly = 1) { fixture.workManager.enqueueUniqueWork("cache:book", ExistingWorkPolicy.KEEP, work) }

        val existingWork = mockk<WorkInfo>()
        every { existingWork.state } returns WorkInfo.State.RUNNING
        val completedEarlier = mockk<WorkInfo>()
        every { completedEarlier.state } returns WorkInfo.State.SUCCEEDED
        val workState = MutableStateFlow(listOf(completedEarlier, existingWork))
        every { fixture.workManager.getWorkInfosForUniqueWorkFlow("cache:book") } returns workState
        completion.set(Operation.SUCCESS)
        assertSame(existingWork, observed.first())
        verify(exactly = 1) { fixture.workManager.getWorkInfosForUniqueWorkFlow("cache:book") }
    }

    @Test
    fun cacheAndExportWaitForEnqueueBeforeReadingTerminalRecords() = runTest {
        for (export in listOf(false, true)) {
            val env = BookRepositoryFixture()
            val name = if (export) ExportBookToEPUBWork.ofId("book") else CacheBookWork.ofId("book")
            val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
            val operation = mockk<Operation> { every { result } returns completion }
            every { env.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } returns operation
            fun completedWork(tag: Int) = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.SUCCEEDED
                every { tags } returns setOf("lightnovelreader:work-submission:$tag")
                every { generation } returns 0
                every { runAttemptCount } returns 0
                every { id } returns UUID.randomUUID()
            }
            val old = completedWork(1)
            val current = completedWork(2)
            val infos = MutableStateFlow(listOf(old))
            every { env.workManager.getWorkInfosForUniqueWorkFlow(name) } returns infos
            val observed = if (export) {
                DetailViewModel(env.repository(), mockk(), mockk(), env.workManager)
                    .exportToEpub(Uri.parse("content://exports/new.epub"), "book", "Title")
            } else env.repository().cacheBook("book")
            val first = async { observed.first() }
            runCurrent()
            assertFalse(first.isCompleted)
            verify(exactly = 0) { env.workManager.getWorkInfosForUniqueWorkFlow(name) }

            infos.value = listOf(current)
            completion.set(Operation.SUCCESS)
            runCurrent()
            assertSame(current, first.await())
        }
    }

    @Test
    fun tagsAreForwardedUnchangedToTheCurrentProviderWithTheSameController() {
        val controller = mockk<NavController>()
        val replacement = mockk<ProxyWebBookDataSource>()
        every { fixture.remote.progressBookTagClick(any(), any()) } just Runs
        every { replacement.progressBookTagClick(any(), any()) } just Runs
        val repository = fixture.repository()
        repository.progressBookTagClick("tag / value", controller)
        fixture.activeRemote = replacement
        repository.progressBookTagClick("next", controller)
        verify(exactly = 1) { fixture.remote.progressBookTagClick("tag / value", controller) }
        verify(exactly = 1) { replacement.progressBookTagClick("next", controller) }
    }

    @Test
    fun cacheStatusKeepsMissingEmptyAndPartiallyCachedVolumeSemantics() = runTest {
        val repository = fixture.repository()
        coEvery { fixture.local.getBookVolumes("book") } returns null
        assertFalse(repository.getIsBookCached("book"))
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes("book", emptyList())
        assertFalse(repository.getIsBookCached("book"))
        val volume = Volume("volume", "title", emptyList())
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes("book", listOf(volume))
        assertTrue(repository.getIsBookCached("book"))
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes(
            "book", listOf(volume.copy(chapters = listOf(ChapterInformation("first", "1"), ChapterInformation("second", "2")))),
        )
        coEvery { fixture.local.isChapterContentExists("first") } returns true
        coEvery { fixture.local.isChapterContentExists("second") } returns false
        assertFalse(repository.getIsBookCached("book"))
        coEvery { fixture.local.isChapterContentExists("second") } returns true
        assertTrue(repository.getIsBookCached("book"))
    }

    @Test
    fun readingUpdatesUseTheStoredValueAndRetainTheLocalObservationFlow() = runTest {
        val observed = MutableStateFlow(UserReadingData("book", totalReadTime = 10))
        coEvery { fixture.local.getUserReadingData("book") } answers { observed.value }
        coEvery { fixture.local.getAllUserReadingData() } answers { listOf(observed.value) }
        every { fixture.local.getUserReadingDataFlow("book") } returns observed
        coEvery { fixture.local.updateUserReadingData("book", any()) } answers {
            observed.value = secondArg<(UserReadingData) -> UserReadingData>()(observed.value)
        }
        val repository = fixture.repository()
        var transformations = 0
        assertSame(observed, repository.getUserReadingDataFlow("book"))
        repository.updateUserReadingData("book") {
            transformations++
            it.copy(totalReadTime = it.totalReadTime + 5, lastReadChapterId = "chapter")
        }
        val expected = UserReadingData("book", totalReadTime = 15, lastReadChapterId = "chapter")
        assertEquals(expected, repository.getUserReadingData("book"))
        assertEquals(listOf(expected), repository.getAllUserReadingData())
        assertEquals(expected, observed.value)
        assertEquals(1, transformations)
        coVerify(exactly = 1) { fixture.local.updateUserReadingData("book", any()) }
    }
}
