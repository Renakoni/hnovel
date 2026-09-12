package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.Application
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.UUID

internal fun workerParameters(data: Data, workId: UUID = UUID.randomUUID()) = mockk<WorkerParameters>(relaxed = true) {
    every { inputData } returns data
    every { id } returns workId
    every { tags } returns emptySet()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceWorkerTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")
    private val time = LocalDateTime.of(2026, 9, 9, 0, 0)
    private fun info(id: String) = BookInformation(id, "Same title", author = "Same author", description = "",
        publishingHouse = "", wordCount = WordCount(1), lastUpdated = time, isComplete = false)

    @Test fun recreatedCacheWorkersRouteAndStoreSameRemoteIdsSeparately() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val fixture = BookRepositoryFixture()
        coEvery { fixture.local.getBookInformation(any()) } returns null
        coEvery { fixture.local.getBookVolumes(any()) } returns null
        coEvery { fixture.local.getChapterContent(any()) } returns null
        coEvery { fixture.local.updateBookInformation(any()) } just Runs
        coEvery { fixture.local.updateBookVolumes(any()) } just Runs
        coEvery { fixture.local.updateChapterContent(any()) } just Runs
        coEvery { fixture.bookshelves.getBookshelfBookMetadata(any()) } returns null
        every { fixture.text.processBookInformation(any()) } answers { firstArg<() -> BookInformation>()() }
        every { fixture.text.processBookVolumes(any()) } answers { firstArg<() -> BookVolumes>()() }
        every { fixture.text.processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        val items = mutableListOf<DownloadItem>()
        val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
        for (book in listOf(a, b)) {
            val runtime = mockk<SourceRuntime> {
                coEvery { getBookInformation("same", any()) } returns Ok(info("same"))
                coEvery { getBookVolumes("same", any()) } returns Ok(BookVolumes("same", listOf(Volume("v", "Volume", listOf(ChapterInformation("c", "Chapter"))))))
                coEvery { getChapterContent("c", "same", any()) } returns Ok(ChapterContent("c", book.sourceId.id, JsonObject(emptyMap())))
            }
            coEvery { fixture.registry.resolve(book.sourceId) } returns SourceResolution.Ready(runtime)
            val data = workDataOf("bookId" to book.storageKey)
            repeat(2) {
                val worker = CacheBookWork(context, workerParameters(data), fixture.local, progress, fixture.repository(), mockk(relaxed = true))
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
            }
            coVerify(exactly = 2) { runtime.getChapterContent("c", "same", any()) }
            coVerify { fixture.local.updateChapterContent(match { it.id == SourceChapterId(book, "c").storageKey && it.title == book.sourceId.id }) }
        }
        assertEquals(listOf(a.storageKey, a.storageKey, b.storageKey, b.storageKey), items.map { it.bookId })
        assertTrue(items.all { it.progress == 1f })
    }

    @Test fun missingSourceAndLoginRequiredAreTerminalFailuresWithSafeRecoverableReasons() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = mockk<BookRepository>()
        every { repository.getBookInformationFlow(any<String>(), any()) } returns kotlinx.coroutines.flow.emptyFlow()
        val items = mutableListOf<DownloadItem>()
        val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
        for ((kind, reason) in listOf(WebRequestErrorKind.SourceUnavailable to "source_unavailable",
            WebRequestErrorKind.AuthenticationRequired to "authentication_required")) {
            every { repository.getBookVolumesFlow(a.storageKey, any()) } returns kotlinx.coroutines.flow.flowOf(
                Err(WebRequestError("Sign in", "Do not persist this private detail", kind = kind)))
            val worker = CacheBookWork(context, workerParameters(workDataOf("bookId" to a.storageKey)), mockk(), progress, repository, mockk(relaxed = true))
            val result = worker.doWork() as ListenableWorker.Result.Failure
            assertEquals(reason, result.outputData.getString("reason"))
            assertEquals(a.storageKey, result.outputData.getString("bookId"))
            assertFalse(result.outputData.toString().contains("private detail"))
        }
        assertTrue(items.all { it.progress == -1f })
    }

    @Test fun mixedUpdateBatchReportsFailuresIndividuallyAndContinuesWithOtherSources() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val books = mockk<BookRepository> {
            coEvery { refreshBookInformation(a, any()) } returns Err(WebRequestError("Login", "secret", kind = WebRequestErrorKind.AuthenticationRequired))
            coEvery { refreshBookInformation(b, any()) } returns Ok(info(b.storageKey))
        }
        val shelves = mockk<BookshelfRepository> {
            coEvery { getAllBookshelves() } returns listOf(Bookshelf(id = 1, systemUpdateReminder = true, allBookIds = listOf(a.storageKey, b.storageKey)))
            coEvery { getAllBookshelfBooksMetadata() } returns listOf(a, b).map { book -> mockk {
                every { id } returns book.storageKey
                every { lastUpdate } returns time.minusDays(1)
            } }
        }
        val work = CheckUpdateWork(context, workerParameters(Data.EMPTY), books, shelves)
        val result = work.doWork() as ListenableWorker.Result.Success
        assertEquals(2, result.outputData.getInt("checkedCount", -1))
        assertEquals(1, result.outputData.getInt("updatedCount", -1))
        assertEquals(1, result.outputData.getInt("failedCount", -1))
        val report = context.filesDir.resolve("book-update-results").resolve(result.outputData.getString("report")!!).readText()
        val outcomes = Json.parseToJsonElement(report).jsonArray.associate { it.jsonObject["bookId"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.content }
        assertEquals(mapOf(a.storageKey to "authentication_required", b.storageKey to "updated"), outcomes)
        assertFalse(report.contains("secret"))
        coVerify(exactly = 1) { books.refreshBookInformation(a, any()) }
        coVerify(exactly = 1) { books.refreshBookInformation(b, any()) }
    }
}
