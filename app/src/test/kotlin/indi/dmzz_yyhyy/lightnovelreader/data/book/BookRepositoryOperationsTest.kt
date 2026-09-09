package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.app.Application
import android.net.Uri
import androidx.concurrent.futures.ResolvableFuture
import androidx.room.Room
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.impl.WorkContinuationImpl
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.EnqueueRunnable
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryOperationsTest {
    private val fixture = BookRepositoryFixture()

    @Test
    fun singleKeepWorkRetainsActiveIdentityAndReplacesTerminalRowsAcrossClockChanges() {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WorkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var time = 2_000L
        val config = Configuration.Builder().setClock(object : Clock {
            override fun currentTimeMillis() = time
        }).build()
        val manager = mockk<WorkManagerImpl> {
            every { workDatabase } returns database
            every { configuration } returns config
            every { schedulers } returns emptyList()
        }
        fun enqueue(request: OneTimeWorkRequest) {
            EnqueueRunnable.addToDatabase(WorkContinuationImpl(manager, CacheBookWork.ofId(BookIdentity.bookKey("book")), ExistingWorkPolicy.KEEP, listOf(request)))
        }
        fun request() = androidx.work.OneTimeWorkRequestBuilder<CacheBookWork>().build()
        try {
            val old = request()
            enqueue(old)
            val ignored = request()
            enqueue(ignored)
            assertEquals(listOf(old.id.toString()), database.workSpecDao().getWorkSpecIdAndStatesForName(CacheBookWork.ofId(BookIdentity.bookKey("book"))).map { it.id })
            assertNull(database.workSpecDao().getWorkSpec(ignored.id.toString()))

            database.workSpecDao().setState(WorkInfo.State.SUCCEEDED, old.id.toString())
            time = 1_000L
            val replacement = request()
            enqueue(replacement)
            assertEquals(listOf(replacement.id.toString()), database.workSpecDao().getWorkSpecIdAndStatesForName(CacheBookWork.ofId(BookIdentity.bookKey("book"))).map { it.id })
            assertNull(database.workSpecDao().getWorkSpec(old.id.toString()))
        } finally {
            database.close()
        }
    }

    @Test
    fun cacheWorkKeepsItsWorkerInputAndObservesTheUniqueWorkIdentity() = runTest {
        val submitted = slot<OneTimeWorkRequest>()
        val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation> { every { result } returns completion }
        every { fixture.workManager.enqueueUniqueWork(CacheBookWork.ofId(BookIdentity.bookKey("book")), ExistingWorkPolicy.KEEP, capture(submitted)) } returns operation
        val repository = fixture.repository()
        val observed = repository.cacheBook("book")
        val work = submitted.captured
        assertEquals(CacheBookWork::class.java.name, work.workSpec.workerClassName)
        assertEquals(mapOf("bookId" to BookIdentity.bookKey("book")), work.workSpec.input.keyValueMap)
        verify(exactly = 1) { fixture.workManager.enqueueUniqueWork(CacheBookWork.ofId(BookIdentity.bookKey("book")), ExistingWorkPolicy.KEEP, work) }

        val existingWork = mockk<WorkInfo>()
        every { existingWork.state } returns WorkInfo.State.RUNNING
        val workState = MutableStateFlow(listOf(existingWork))
        every { fixture.workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(BookIdentity.bookKey("book"))) } returns workState
        completion.set(Operation.SUCCESS)
        assertSame(existingWork, observed.first())
        verify(exactly = 1) { fixture.workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(BookIdentity.bookKey("book"))) }
    }

    @Test
    fun cacheAndExportWaitForEnqueueBeforeReadingTerminalRecords() = runTest {
        for ((source, export) in listOf("a" to false, "b" to false, "a" to true, "b" to true)) {
            val book = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", source), "same")
            val env = BookRepositoryFixture()
            val name = if (export) ExportBookToEPUBWork.ofId(book.storageKey) else CacheBookWork.ofId(book.storageKey)
            val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
            val operation = mockk<Operation> { every { result } returns completion }
            every { env.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } returns operation
            fun completedWork() = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.SUCCEEDED
            }
            val old = completedWork()
            val current = completedWork()
            val infos = MutableStateFlow(listOf(old))
            every { env.workManager.getWorkInfosForUniqueWorkFlow(name) } returns infos
            val observed = if (export) {
                DetailViewModel(env.repository(), mockk(), mockk(), env.workManager)
                    .exportToEpub(Uri.parse("content://exports/new.epub"), book.storageKey, "Title")
            } else env.repository().cacheBook(book.storageKey)
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
    fun volumeCoverCallbackReceivesOnlyItsSourcesRemoteIds() = runTest {
        val repository = fixture.repository()
        for (source in listOf("a", "b")) {
            val book = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", source), "same")
            val runtime = mockk<indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime>()
            coEvery { fixture.registry.resolve(book.sourceId) } returns indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Ready(runtime)
            val remoteVolume = Volume("volume", "Same title", listOf(ChapterInformation("chapter", "Chapter")))
            val remoteContent = io.nightfish.lightnovelreader.api.book.ChapterContent("chapter", "Chapter",
                kotlinx.serialization.json.JsonObject(emptyMap()), nextChapter = "next")
            val volume = book.bind(BookVolumes(book.remoteId, listOf(remoteVolume))).volumes.single()
            val chapter = SourceChapterId(book, "chapter").bind(remoteContent)
            val context = RuntimeEnvironment.getApplication()
            val cover = Uri.parse("https://fixture.invalid/$source.jpg")
            coEvery { runtime.volumeCover("same", remoteVolume, mutableMapOf("chapter" to remoteContent), context) } returns cover
            assertEquals(com.github.michaelbull.result.Ok(cover), repository.volumeCover(book, volume, mapOf(chapter.id to chapter), context))
            coVerify(exactly = 1) { runtime.volumeCover("same", remoteVolume, mutableMapOf("chapter" to remoteContent), context) }
        }
        verify(exactly = 0) { fixture.provider.value }
    }

    @Test
    fun tagsUseTheBookSourceAndReturnDataForHostNavigation() = runTest {
        val bookA = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "a"), "same")
        val bookB = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "b"), "same")
        val a = mockk<indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime>()
        val b = mockk<indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime>()
        every { a.bookTagPage("tag") } returns "page-a"
        every { b.bookTagPage("tag") } returns "page-b"
        coEvery { fixture.registry.resolve(bookA.sourceId) } returns indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Ready(a)
        coEvery { fixture.registry.resolve(bookB.sourceId) } returns indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Ready(b)
        val repository = fixture.repository()
        assertEquals(com.github.michaelbull.result.Ok("page-a"), repository.bookTagPage(bookA, "tag"))
        assertEquals(com.github.michaelbull.result.Ok("page-b"), repository.bookTagPage(bookB, "tag"))
        assertEquals(com.github.michaelbull.result.Ok("page-a"), repository.bookTagPage(bookA, "tag"))
        coEvery { fixture.registry.resolve(bookA.sourceId) } returns indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Missing(bookA.sourceId)
        assertTrue(repository.bookTagPage(bookA, "tag").isErr)
        verify(exactly = 2) { a.bookTagPage("tag") }
        verify(exactly = 1) { b.bookTagPage("tag") }
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
