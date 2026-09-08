package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.app.Application
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookRepositoryOperationsTest {
    private val fixture = BookRepositoryFixture()

    @Test
    fun cacheWorkKeepsItsWorkerInputAndObservesTheUniqueWorkIdentity() = runTest {
        val submitted = slot<OneTimeWorkRequest>()
        every { fixture.workManager.enqueueUniqueWork("cache:book", ExistingWorkPolicy.KEEP, capture(submitted)) } returns mockk()
        val repository = fixture.repository()
        val work = repository.cacheBook("book")
        assertSame(work, submitted.captured)
        assertEquals(CacheBookWork::class.java.name, work.workSpec.workerClassName)
        assertEquals(mapOf("bookId" to "book"), work.workSpec.input.keyValueMap)
        verify(exactly = 1) { fixture.workManager.enqueueUniqueWork("cache:book", ExistingWorkPolicy.KEEP, work) }

        val existingWork = mockk<WorkInfo>()
        every { existingWork.state } returns WorkInfo.State.RUNNING
        val completedEarlier = mockk<WorkInfo>()
        every { completedEarlier.state } returns WorkInfo.State.SUCCEEDED
        val workState = MutableStateFlow(listOf(completedEarlier, existingWork))
        every { fixture.workManager.getWorkInfosForUniqueWorkFlow("cache:book") } returns workState
        assertSame(existingWork, repository.isCacheBookWorkFlow("book").first())
        verify(exactly = 1) { fixture.workManager.getWorkInfosForUniqueWorkFlow("cache:book") }
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
