package indi.renakoni.nextvol.data.work

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.utils.ImageUtils
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.dom4j.DocumentHelper
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.zip.ZipFile

/** Actual worker, component decoder and ZIP writer; provider and network failures are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class EpubExportRegressionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val book = SourceBookId(Identifier("fixture", "epub71"), "book")
    private val repository = mockk<BookRepository>()
    private val items = mutableListOf<DownloadItem>()
    private val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
    private val decoder = ContentJsonDecoder(ContentComponentRegistry())
    private var volumes = book.bind(BookVolumes("book", (1..2).map {
        Volume("v$it", "Same volume", listOf(ChapterInformation("c$it", "Chapter $it")))
    }))
    private val files = mutableListOf<File>()
    private lateinit var output: File
    private var streamFactory: (File, Int) -> OutputStream = { file, _ -> file.outputStream() }

    @Before fun prepare() {
        stubExportRepository(repository)
        mockkStatic(android.provider.DocumentsContract::class)
        every { android.provider.DocumentsContract.deleteDocument(context.contentResolver, any()) } answers {
            File(secondArg<Uri>().lastPathSegment!!).delete()
        }
        mockkStatic(DocumentFile::class)
        mockkObject(ImageUtils)
        val folder = mockk<DocumentFile> {
            every { createFile(any(), any()) } answers {
                val file = output.resolve(secondArg<String>())
                files.add(file)
                val outputUri = Uri.parse("content://fixture/epub71/${Uri.encode(file.absolutePath)}")
                Shadows.shadowOf(context.contentResolver).registerOutputStream(outputUri, streamFactory(file, files.size))
                mockk { every { uri } returns outputUri }
            }
        }
        every { DocumentFile.fromTreeUri(context, any()) } returns folder
        every { repository.getBookInformationFlow(book.storageKey, any()) } returns flowOf(Ok(book.bind(
            BookInformation("book", "Probe book", author = "Author", description = "", publishingHouse = "", wordCount = WordCount(2),
                lastUpdated = LocalDateTime.of(2026, 9, 19, 0, 0), isComplete = false)
        )))
        every { repository.getBookVolumesFlow(book.storageKey, any()) } answers { flowOf(Ok(volumes)) }
        coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
        setBody(textBody("Retained text"))
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns
            Ok(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    }

    @After fun finish() {
        unmockkObject(ImageUtils)
        unmockkStatic(DocumentFile::class)
        unmockkStatic(android.provider.DocumentsContract::class)
    }

    private fun startCase(name: String) {
        output = File("build/epub-compliance/issue71", name).apply { mkdirs() }
    }

    private fun textBody(text: String, id: String = "lightnovelreader:simple_text") = buildJsonObject {
        putJsonArray("components") { add(buildJsonObject {
            put("id", id); put("data", SimpleTextComponentData(text).toJsonElement())
        }) }
    }

    private fun imageBody(url: String, count: Int = 1) = buildJsonObject {
        putJsonArray("components") { repeat(count) { add(buildJsonObject {
            put("id", "lightnovelreader:image")
            put("data", ImageComponentData(Uri.parse(url)).toJsonElement())
        }) } }
    }

    private fun setBody(body: JsonObject) {
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            flowOf(Ok(ChapterContent(firstArg(), "Chapter", body)))
        }
    }

    private fun worker(type: String = "VOLUMES", images: Boolean = true, created: Boolean = false,
                       selected: String = volumes.volumes.joinToString(",") { it.volumeId }): ExportBookToEPUBWork {
        val uri = if (type == "VOLUMES") Uri.parse("content://fixture/tree/epub71") else {
            val file = output.resolve("book.epub").also(files::add)
            Uri.parse("content://fixture/epub71/${Uri.encode(file.absolutePath)}").also {
                file.createNewFile() // The picker creates a document; the provider opens it only when copying starts.
                Shadows.shadowOf(context.contentResolver).registerOutputStream(it, object : OutputStream() {
                    private var target: OutputStream? = null
                    private fun stream() = target ?: streamFactory(file, files.size).also { target = it }
                    override fun write(value: Int) = stream().write(value)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = stream().write(bytes, offset, length)
                    override fun close() { target?.close() }
                })
            }
        }
        return ExportBookToEPUBWork(context, workerParameters(workDataOf(
            "bookId" to book.storageKey, "title" to "Probe book", "exportType" to type,
            "selectedVolume" to selected, "includeImages" to images, "uri" to uri.toString(), "createdDocument" to created
        )), repository, progress, decoder, exportDownloads())
    }

    private fun bodies(file: File): List<String> = ZipFile(file).use { zip ->
        val opf = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/content.opf")).reader().readText())
        val manifest = opf.rootElement.element("manifest").elements("item")
        opf.rootElement.element("spine").elements("itemref").map { ref ->
            val href = manifest.single { it.attributeValue("id") == ref.attributeValue("idref") }.attributeValue("href")
            val doc = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/$href")).reader().readText())
            doc.rootElement.element("body").stringValue
        }
    }

    @Test fun normalSameNamedVolumesExportInCatalogOrderEvenWhenSelectionIsReversed() = runTest {
        startCase("normal-batch")
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            val id = firstArg<String>()
            flowOf(Ok(ChapterContent(id, "Chapter", textBody(id))))
        }
        assertTrue(worker(selected = volumes.volumes.reversed().joinToString(",") { it.volumeId }).doWork() is ListenableWorker.Result.Success)
        assertEquals(2, files.size)
        assertEquals(volumes.volumes.flatMap { it.chapters }.map { it.id }, files.flatMap(::bodies))
    }

    @Test fun includeImagesFalseDoesNotFetchOrPackageIllustrations() = runTest {
        startCase("images-disabled")
        setBody(imageBody("https://fixture.invalid/missing.jpg"))
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns Err(IOException("missing illustration"))
        val result = worker(images = false).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) }
        assertEquals(2, files.size)
        files.forEach { file -> ZipFile(file).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("EPUB/image/") })
        } }
    }

    @Test fun badImageInSecondVolumeBlocksGoodFirstVolumeBeforeAnyOutput() = runTest {
        startCase("one-bad-image")
        val badId = volumes.volumes[1].chapters.single().id
        every { repository.getChapterContentFlow(badId, book.storageKey, any()) } returns
            flowOf(Ok(ChapterContent(badId, "Chapter", imageBody("https://fixture.invalid/missing.jpg"))))
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns Err(IOException("missing illustration"))
        assertTrue(worker().doWork() is ListenableWorker.Result.Failure)
        assertTrue(files.isEmpty())
    }

    @Test fun duplicateImagesAreAcquiredOncePerExport() = runTest {
        startCase("duplicate-images")
        setBody(imageBody("https://fixture.invalid/repeated.jpg", 3))
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) }
        files.forEach { file -> ZipFile(file).use { zip ->
            assertEquals(1, zip.entries().asSequence().count { it.name.startsWith("EPUB/image/") })
        } }
    }

    @Test fun unknownBodyComponentsFailWithChapterLocationBeforePublishing() = runTest {
        startCase("silent-body-loss")
        setBody(textBody("This text must not disappear", "missing-plugin:text"))
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("invalid_content", result.outputData.getString("reason"))
        assertEquals("Chapter 1", result.outputData.getString("chapter"))
        assertTrue(files.isEmpty())
        assertEquals(-1f, items.single().progress)
    }

    @Test fun legacyShortComponentIdsRetainTheirText() = runTest {
        startCase("legacy-id-body-loss")
        setBody(textBody("Legacy text", "simple_text"))
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf("Legacy text", "Legacy text"), files.flatMap(::bodies))
    }

    @Test fun emptyVolumeFailsWithoutEscapingWorkerFailureHandling() = runTest {
        startCase("empty-volume")
        volumes = book.bind(BookVolumes("book", listOf(Volume("empty", "Empty volume", emptyList()))))
        val result = worker("BOOK").doWork() as ListenableWorker.Result.Failure
        assertEquals("empty_volume", result.outputData.getString("reason"))
        assertEquals(-1f, items.single().progress)
    }

    @Test fun failedSecondProviderCopyKeepsCompletedFileAndRemovesPartialFile() = runTest {
        startCase("provider-copy-failure")
        streamFactory = { file, index ->
            assertTrue("No output is final until every destination closes", items.single().progress < 1f)
            val target = file.outputStream()
            if (index == 1) target else object : OutputStream() {
                override fun write(value: Int) { throw IOException("disk full") }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    target.write(bytes, offset, minOf(length, 80)); target.flush(); throw IOException("disk full")
                }
                override fun close() = target.close()
            }
        }
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("save_failed", result.outputData.getString("reason"))
        assertEquals(1, result.outputData.getInt("completedVolumes", -1))
        assertEquals(2, result.outputData.getInt("totalVolumes", -1))
        assertFalse(result.outputData.getBoolean("cleanupFailed", true))
        assertEquals(2, files.size)
        assertEquals(listOf("Retained text"), bodies(files.first()))
        assertFalse(files[1].exists())
    }

    @Test fun emptyVolumeInSplitModeFailsBeforeCreatingFiles() = runTest {
        startCase("empty-split-volume")
        volumes = book.bind(BookVolumes("book", listOf(Volume("empty", "Empty volume", emptyList()))))
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("empty_volume", result.outputData.getString("reason"))
        assertTrue(files.isEmpty())
    }

    @Test fun cancellationDuringCopyStopsDeliveryAndRemovesIncompleteFile() = runTest {
        startCase("cancel-during-copy")
        lateinit var job: Job
        streamFactory = { file, _ ->
            val target = file.outputStream()
            object : OutputStream() {
                override fun write(value: Int) { target.write(value); job.cancel() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    target.write(bytes, offset, length); job.cancel()
                }
                override fun close() = target.close()
            }
        }
        job = launch(start = CoroutineStart.LAZY) { worker().doWork() }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, files.size)
        assertFalse(files.single().exists())
        assertEquals(-1f, items.single().progress)
    }

    @Test fun collidingVolumeCoverUrlsRemainDistinct() = runTest {
        startCase("volume-cover-collision")
        volumes = book.bind(BookVolumes("book", (1..3).map {
            Volume("v$it", "Volume $it", listOf(ChapterInformation("c$it", "Chapter $it")))
        }))
        val urls = listOf(Uri.parse("https://fixture.invalid/Aa.jpg"), Uri.parse("https://fixture.invalid/BB.jpg"))
        assertNotEquals(urls[0], urls[1]); assertEquals(urls[0].hashCode(), urls[1].hashCode())
        val selected = volumes.volumes.drop(1)
        selected.forEachIndexed { index, volume ->
            coEvery { repository.volumeCover(book, volume, any(), any()) } returns Ok(urls[index])
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) Color.RED else Color.BLUE)
            }
            coEvery { ImageUtils.uriToBitmap(urls[index], context, book.storageKey, any(), any(), false) } returns Ok(bitmap)
        }
        assertTrue(worker(selected = selected.joinToString(",") { it.volumeId }).doWork() is ListenableWorker.Result.Success)
        val covers = files.map { file -> ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("EPUB/cover.jpg")).readBytes() } }
        assertEquals(2, covers.size); assertFalse(covers[0].contentEquals(covers[1]))
    }

    @Test fun closeFailureNeverRecordsCompletedExport() = runTest {
        startCase("close-failure")
        streamFactory = { file, _ -> object : java.io.FilterOutputStream(file.outputStream()) {
            override fun close() { super.close(); throw IOException("provider close failed") }
        } }
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals(0, result.outputData.getInt("completedVolumes", -1))
        assertEquals(-1f, items.single().progress)
        assertEquals(1, files.size)
        assertFalse(files.single().exists())
    }

    @Test fun optionalVolumeCoverLookupFailureUsesDefaultCover() = runTest {
        startCase("cover-lookup-failure")
        coEvery { repository.volumeCover(book, any(), any(), any()) } throws IOException("cover unavailable")
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf("Retained text", "Retained text"), files.flatMap(::bodies))
    }

    @Test fun malformedContentFailsBeforeCreatingFiles() = runTest {
        startCase("malformed-content")
        setBody(JsonObject(emptyMap()))
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("invalid_content", result.outputData.getString("reason"))
        assertTrue(files.isEmpty())
    }

    @Test fun pickerCreatedBookIsRemovedAfterFailureAndCleanupFailureIsReported() = runTest {
        startCase("book-cleanup")
        setBody(JsonObject(emptyMap()))
        val first = worker("BOOK", created = true).doWork() as ListenableWorker.Result.Failure
        assertFalse(files.single().exists())
        assertFalse(first.outputData.getBoolean("cleanupFailed", true))
        every { android.provider.DocumentsContract.deleteDocument(context.contentResolver, any()) } returns false
        val second = worker("BOOK", created = true).doWork() as ListenableWorker.Result.Failure
        assertTrue(second.outputData.getBoolean("cleanupFailed", false))
        assertTrue(second.outputData.getString("message")!!.contains(context.getString(
            indi.renakoni.nextvol.R.string.epub_export_cleanup_failed)))
    }

    @Test fun longUnicodeVolumeNamesKeepDistinctSuffixAndExtension() = runTest {
        startCase("long-volume-names")
        volumes = volumes.copy(volumes = volumes.volumes.map { it.copy(volumeTitle = "长标题".repeat(100)) })
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(2, files.map { it.name }.distinct().size)
        files.forEach { file ->
            assertTrue(file.name.endsWith(".epub"))
            assertTrue(file.name.toByteArray(Charsets.UTF_8).size <= 255)
        }
    }
}
