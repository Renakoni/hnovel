package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceExportWorkerTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")

    @Test fun fullAndSelectedVolumeExportsKeepSameNamedBooksAndVolumesIndependent() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = mockk<BookRepository>()
        val items = mutableListOf<DownloadItem>()
        val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
        val output = context.filesDir.resolve("exports-${UUID.randomUUID()}").apply { mkdirs() }
        fun outputUri(name: String): Uri = Uri.parse("content://fixture/exports/${Uri.encode(name)}").also {
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(it, output.resolve(name).outputStream())
        }
        val tree = Uri.parse("content://fixture/tree/exports")
        val names = mutableListOf<String>()
        val folder = mockk<DocumentFile> {
            every { createFile(any(), capture(names)) } answers {
                val fileUri = outputUri(secondArg<String>())
                mockk { every { uri } returns fileUri }
            }
        }
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, tree) } returns folder
        try {
            for (book in listOf(a, b)) {
                val volumes = book.bind(BookVolumes("same", listOf("v1", "v2").map {
                    Volume(it, "Same volume", listOf(ChapterInformation("c-$it", "Chapter")))
                }))
                val info = book.bind(BookInformation("same", "Same title", author = "Same author",
                    description = "", publishingHouse = "", wordCount = WordCount(1),
                    lastUpdated = LocalDateTime.of(2026, 9, 9, 0, 0), isComplete = false))
                every { repository.getBookInformationFlow(book.storageKey, any()) } returns flowOf(Ok(info))
                every { repository.getBookVolumesFlow(book.storageKey, any()) } returns flowOf(Ok(volumes))
                for (chapter in volumes.volumes.flatMap { it.chapters }) {
                    every { repository.getChapterContentFlow(chapter.id, book.storageKey, any()) } returns
                        flowOf(Ok(ChapterContent(chapter.id, "Chapter", JsonObject(emptyMap()))))
                }
                coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
                for (type in listOf("BOOK", "VOLUMES")) {
                    val workId = UUID.randomUUID()
                    val data = workDataOf("bookId" to book.storageKey, "exportType" to type,
                        "selectedVolume" to volumes.volumes.joinToString(",") { it.volumeId },
                        "uri" to (if (type == "BOOK") outputUri("${book.fileKey}.epub") else tree).toString())
                    val otherWork = context.cacheDir.resolve("epub/${book.fileKey}/other-work/keep").apply {
                        parentFile!!.mkdirs(); writeText("keep")
                    }
                    val worker = ExportBookToEPUBWork(context, workerParameters(data, workId), repository, progress, mockk(relaxed = true))
                    assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("ExportEPUB").toString(),
                        ListenableWorker.Result.success(), worker.doWork())
                    assertFalse(context.cacheDir.resolve("epub/${book.fileKey}/$workId").exists())
                    assertTrue(otherWork.exists())
                }
                coVerify(exactly = 1) { repository.volumeCover(book, match { it.volumeId == volumes.volumes[1].volumeId }, any(), any()) }
            }
            assertEquals(4, names.distinct().size)
            assertEquals(6, output.listFiles()!!.size)
            for (file in output.listFiles()!!) ZipFile(file).use { assertTrue(it.size() > 0) }
            assertTrue(items.all { it.progress == 1f })
            assertEquals(listOf(a.storageKey, a.storageKey, b.storageKey, b.storageKey), items.map { it.bookId })
        } finally {
            unmockkStatic(DocumentFile::class)
            output.deleteRecursively()
        }
    }

    @Test fun selectedVolumeFromAnotherSourceIsRejectedBeforeRepositoryAccess() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = mockk<BookRepository>()
        val data = workDataOf("bookId" to a.storageKey, "exportType" to "VOLUMES",
            "selectedVolume" to BookIdentity.volumeKey(b, "v1"))
        val result = ExportBookToEPUBWork(context, workerParameters(data), repository, mockk(), mockk()).doWork()
            as ListenableWorker.Result.Failure
        assertEquals("invalid_volume_identity", result.outputData.getString("reason"))
        verify { repository wasNot Called }
    }
}
