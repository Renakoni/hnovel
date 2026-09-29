package indi.renakoni.nextvol.data.download

import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.*
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.LocalDataManager
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.image.SourceImageProvider
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime

/** Real Room, source response cache, source images and download worker; no external site needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class BookDownloadTest {
    companion object { const val IMAGE = "https://fixture.invalid/illustration.png" }
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = directory.root.resolve("files").apply { mkdirs() }
    } }
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")
    private val registry = WebSourceRegistry()
    private val decoder = ContentJsonDecoder(ContentComponentRegistry())
    private lateinit var db: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var downloads: BookDownloadStore
    private lateinit var books: BookRepository
    private lateinit var cache: DiskCache
    private lateinit var loader: ImageLoader
    private val progress = mockk<DownloadProgressRepository>(relaxed = true)
    private val png by lazy { ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray() }

    @Before fun setUp() {
        openLibrary()
        cache = DiskCache.Builder().directory(directory.root.resolve("coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
        openImages()
    }

    private fun openLibrary() {
        db = Room.databaseBuilder(context, NextVolDatabase::class.java, directory.root.resolve("library.db").path)
            .addMigrations(NextVolDatabase.MIGRATION_17_18, NextVolDatabase.MIGRATION_18_19,
                NextVolDatabase.MIGRATION_19_20, NextVolDatabase.MIGRATION_20_21, NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24, NextVolDatabase.MIGRATION_24_25, NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27).allowMainThreadQueries().build()
        local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
        downloads = BookDownloadStore(context, db, decoder)
        val shelves = BookshelfRepository(db.bookshelfDao(), mockk(relaxed = true), registry, downloads, local.aliases)
        val text = TextProcessingRepository(mockk { every { enabled } returns false },
            mockk { every { enabled } returns false }, ContentComponentRegistry())
        val workManager = mockk<androidx.work.WorkManager>(relaxed = true) {
            every { getWorkInfoByIdFlow(any()) } returns flowOf(null)
            every { getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        }
        books = BookRepository(local, shelves, text, workManager, ChapterRepository(registry, local, text, mockk(), downloads),
            BookReadingDataRepository(local), registry, downloads, mockk())
    }

    private fun openImages() {
        loader = ImageLoader.Builder(context).diskCache(cache).components {
            add(SourceImageInterceptor(registry, context, downloads))
            add(SourceImageFetcher.Factory())
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun close() = runBlocking {
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close()
    }

    private fun register(book: SourceBookId, revision: String = "1", source: Remote = Remote(book)) = source.also {
        registry.register(it, SourceMetadata(WebDataSourceItem(book.sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent),
            revision = revision))
    }

    private suspend fun download(book: SourceBookId = a, generation: Long = downloads.generation()) =
        CacheBookWork(context, workerParameters(workDataOf("bookId" to book.storageKey, "downloadGeneration" to generation)),
            progress, books, downloads).doWork()

    private suspend fun state(book: SourceBookId = a) = books.downloadState(book.storageKey)
    private suspend fun chapter(book: SourceBookId, id: String) = local.getChapterContent(SourceChapterId(book, id).storageKey)

    @Test fun downloadsSurviveReadingCacheClearSourceRemovalAndHostReconstruction() = runBlocking {
        for (book in listOf(a, b)) {
            register(book).withImages = true
            assertEquals(ListenableWorker.Result.success(), download(book))
        }
        val online = SourceBookId(a.sourceId, "online-only")
        local.updateChapterContent(SourceChapterId(online, "1").bind(ChapterContent("1", "Online", ContentBuilder().simpleText("temporary").build())))
        local.updateUserReadingData(a.storageKey) { it.copy(totalReadTime = 42) }
        downloads.clearReadingCache()
        assertNull(chapter(online, "1"))
        assertNotNull(chapter(a, "1")); assertNotNull(chapter(b, "1"))
        assertEquals(42, local.getUserReadingData(a.storageKey).totalReadTime)
        registry.unregister(a.sourceId); registry.unregister(b.sourceId)
        loader.shutdown(); db.close()
        openLibrary(); openImages()
        for (book in listOf(a, b)) {
            assertEquals(BookDownloadPhase.Complete, state(book).phase)
            val result = loader.execute(ImageRequest.Builder(context).data(SourceImage(book, IMAGE)).build())
            assertTrue("Downloaded image must decode after ordinary disk cache is emptied", result is SuccessResult)
            assertArrayEquals(png, downloads.image(SourceImage(book, IMAGE))!!.readBytes())
        }
        assertNotEquals(downloads.image(SourceImage(a, IMAGE)), downloads.image(SourceImage(b, IMAGE)))
    }

    @Test fun changedCatalogAndRetryReuseOnlySuccessfullySavedUnchangedChapters() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.chapters = source.chapters.map { if (it.id == "2") it.copy(title = "Revised") else it } +
            ChapterInformation("4", "New")
        source.failedChapter = "2"
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        assertEquals("Chapter 2", chapter(a, "2")!!.title)
        source.failedChapter = null
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 3, "3" to 2, "4" to 1), source.chapterCalls)
        assertEquals("Revised", chapter(a, "2")!!.title)
        assertEquals(BookDownloadState(BookDownloadPhase.Complete, 4, 4), state())
        assertEquals(3, source.directoryCalls)
    }

    @Test fun remoteFailureAndEmptyDirectoryDoNotMasqueradeAsSuccessfulUpdates() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.directoryFailed = true
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        assertNotNull(chapter(a, "1"))
        source.directoryFailed = false
        source.chapters = emptyList()
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(3, local.getBookVolumes(a.storageKey)!!.volumes.single().chapters.size)
    }

    @Test fun failedIdentityPreflightRetainsOfflineDataButMarksTheUpdateFailed() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.informationFailed = true
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        assertNotNull(chapter(a, "1"))
        assertEquals(3, local.getBookVolumes(a.storageKey)!!.volumes.single().chapters.size)
    }

    @Test fun missingImagesAreRepairedWithoutRefetchingUnchangedBodies() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertEquals(ListenableWorker.Result.success(), download())
        val image = SourceImage(a, IMAGE)
        assertTrue(downloads.image(image)!!.delete())
        cache.clear()
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun imageFailureAndReadingRefreshKeepPreviouslyDownloadedBody() = runBlocking {
        val source = register(a).apply { withImages = true; extraImage = true }
        assertEquals(ListenableWorker.Result.success(), download())
        val before = chapter(a, "1")!!
        source.chapters = source.chapters.mapIndexed { index, chapter -> if (index == 0) chapter.copy(title = "Changed") else chapter }
        source.failedImage = "$IMAGE?extra"
        source.imageBytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(before, chapter(a, "1"))
        local.updateChapterContent(before.copy(title = "Incomplete reading refresh"))
        assertEquals(before, chapter(a, "1"))
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        assertEquals(1, db.bookDownloadDao().candidates(a.storageKey).size)
        val calls = source.imageCalls
        source.failedImage = null
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(2, source.chapterCalls["1"])
        assertEquals(calls + 1, source.imageCalls)
        assertEquals("Changed", chapter(a, "1")!!.title)
        assertArrayEquals(source.imageBytes, downloads.image(SourceImage(a, IMAGE,
            chapterId = SourceChapterId(a, "1").storageKey))!!.readBytes())
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE,
            chapterId = SourceChapterId(a, "2").storageKey))!!.readBytes())
    }

    @Test fun clearingDownloadsRevokesLateResultsAndPreUpgradeQueuedWork() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val old = async { download(generation = 0) }
        withTimeout(5000) { entered.await() }
        val online = SourceChapterId(b, "read")
        local.updateChapterContent(online.bind(ChapterContent("read", "Online", ContentBuilder().simpleText("keep").build())))
        downloads.clearDownloads()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { old.await() } is ListenableWorker.Result.Failure)
        assertNull(chapter(a, "1")); assertNotNull(chapter(b, "read"))
        assertEquals(BookDownloadPhase.None, state().phase)
        assertTrue(download(generation = 0) is ListenableWorker.Result.Failure)
        source.chapterPause = null
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun sourceRevisionAndRemovedVolumesHaveAccurateUpdateState() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertEquals(BookDownloadPhase.Outdated, state().phase)
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 2, "2" to 2, "3" to 2), source.chapterCalls)
        source.volumeId = "replacement"
        source.chapters = listOf(ChapterInformation("4", "Only new chapter"))
        books.downloadDirectory(a)
        assertEquals(BookDownloadPhase.Outdated, state().phase)
        assertEquals(1, local.getBookVolumes(a.storageKey)!!.volumes.size)
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(BookDownloadState(BookDownloadPhase.Complete, 1, 1), state())
        assertNotNull("Removed remote chapters remain available until download cleanup", chapter(a, "1"))
    }

    @Test fun backupRestoresOwnershipButNeverRunningAttemptsOrMissingImageCompleteness() = runBlocking {
        register(a).withImages = true
        assertEquals(ListenableWorker.Result.success(), download())
        val attempt = downloads.begin(a, downloads.generation(), "active-before-backup")
        val coordinator = StatisticsWriteCoordinator()
        val backup = LocalDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(), db.userReadingDataDao(),
            db.userDataDao(), mockk(relaxed = true), coordinator,
            StatsRepository(db.bookRecordDao(), db.dailyCountDao(), books, coordinator), downloads)
        val saved = backup.exportAppLocalData().get()!!
        val owner = saved.localDataList.single().bookDownloadEntities.single()
        assertEquals("", owner.attempt)
        assertTrue(saved.globalLocalData.userDataEntities.none { it.path.startsWith("hnovel/downloads/") })
        assertTrue(backup.exportCurrentLocalData(localBookCache = false).get()!!.bookDownloadEntities.isEmpty())
        backup.cleanDatabaseWithoutGlobalUserData()
        assertTrue(backup.importAppLocalData(saved).isOk)
        assertNotNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertEquals("", db.bookDownloadDao().get(a.storageKey)!!.attempt)
        try { downloads.finish(attempt, true); fail("Restored backup must not reactivate an old worker") }
        catch (_: CancellationException) { }
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun removingOneBooksDownloadKeepsTheOtherSourceAndRejectsItsOldAttempt() = runBlocking {
        for (book in listOf(a, b)) {
            register(book).withImages = true
            assertEquals(ListenableWorker.Result.success(), download(book))
        }
        val old = downloads.begin(a, downloads.generation(), "old")
        downloads.removeBooks(listOf(a))
        assertNull(chapter(a, "1")); assertNull(downloads.image(SourceImage(a, IMAGE)))
        assertEquals(BookDownloadPhase.None, state(a).phase)
        assertEquals(BookDownloadPhase.Complete, state(b).phase)
        assertNotNull(downloads.image(SourceImage(b, IMAGE)))
        try { downloads.finish(old, true); fail("Removed book must reject its old worker") }
        catch (_: CancellationException) { }
        assertEquals(ListenableWorker.Result.success(), download(a))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun orphanCleanupRetainsDownloadedChaptersRemovedFromRemoteCatalog() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        local.updateBookVolumes(a.bind(BookVolumes(a.remoteId, listOf(Volume("new", "New",
            listOf(ChapterInformation("4", "Next")))))))
        local.updateChapterContent(SourceChapterId(b, "orphan").bind(ChapterContent("orphan", "Temporary",
            ContentBuilder().simpleText("temporary").build())))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val models = androidx.lifecycle.ViewModelStore()
        val model = indi.renakoni.nextvol.ui.bookmanager.BookManagerViewModel(
            books, progress, db, mockk(relaxed = true), mockk(relaxed = true), downloads, mockk(relaxed = true))
        models.put("manager", model)
        val job = model.viewModelScope.coroutineContext[Job]
        try {
            model.clearOrphanedDataItems()
            assertNotNull(chapter(a, "1"))
            assertNull(chapter(b, "orphan"))
        } finally {
            models.clear()
            withTimeout(5000) { job?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test fun room17UpgradeKeepsOldDownloadsAndRetriesAnInterruptedImageMigration() = runBlocking {
        val source = register(a).apply { withImages = true }
        local.updateBookVolumes(a.bind(source.directory()))
        local.updateBookInformation(a.bind(source.information()))
        local.updateChapterContent(SourceChapterId(a, "1").bind(source.body("1")))
        val image = SourceImage(a, IMAGE)
        val key = sourceImageCacheKey(image, "1")
        cache.openEditor(key)!!.let { editor ->
            cache.fileSystem.write(editor.metadata) { }
            cache.fileSystem.write(editor.data) { write(png) }
            editor.commit()
        }
        context.getSharedPreferences("source_image_cache_keys", 0).edit()
            .putString(sourceImageCacheKey(image, ""), key).commit()
        db.userDataDao().insert(UserDataPath.CompletedDownloadBookList.path, "fixture", "CompletedDownloadItemList", "CACHE|${a.storageKey}")
        db.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE downloaded_chapter"); execSQL("DROP TABLE book_download"); execSQL("DROP TABLE local_book_file_manifest"); execSQL("DROP TABLE imported_book")
            execSQL("DROP TABLE bangumi_binding"); execSQL("DROP TABLE bangumi_sync_record"); execSQL("DROP TABLE book_alias"); version = 17
        }
        db.close(); openLibrary()
        assertEquals(27, db.openHelper.writableDatabase.version)
        val blocked = File(context.filesDir, "book-downloads").apply { writeText("not a directory") }
        try { downloads.prepare(); fail("Image copy must fail before ownership is committed") }
        catch (_: java.io.IOException) { }
        assertNull(db.bookDownloadDao().get(a.storageKey))
        assertTrue(blocked.delete())
        downloads.prepare()
        downloads.clearReadingCache()
        assertNotNull(chapter(a, "1"))
        assertArrayEquals(png, downloads.image(image)!!.readBytes())
        assertEquals(BookDownloadState(BookDownloadPhase.Partial, 1, 3), state())
    }

    @Test fun firstRequestFailuresStayVisibleAfterDatabaseReopenAndSourceRemoval() = runBlocking {
        for ((index, stage) in listOf(DownloadStage.Details, DownloadStage.Directory, DownloadStage.Body).withIndex()) {
            val book = SourceBookId(Identifier("fixture", "failure$index"), "same")
            register(book).apply {
                informationFailed = stage == DownloadStage.Details
                directoryFailed = stage == DownloadStage.Directory
                failedChapter = if (stage == DownloadStage.Body) "1" else null
            }
            assertTrue(download(book) is ListenableWorker.Result.Failure)
            registry.unregister(book.sourceId)
            db.close(); openLibrary()
            val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
            try {
                val item = awaitTask(repository, book)
                assertEquals(DownloadTaskStatus.Failed, item.status!!.task.status)
                assertEquals(stage, item.status!!.task.stage)
                assertEquals(DownloadFailure.SourceRequest, item.status!!.task.failure)
                assertEquals(0, item.status!!.content.savedChapters)
                assertEquals(books.downloadStatusFlow(book.storageKey).first(), item.status)
                if (stage == DownloadStage.Details) assertTrue(books.downloadInformationFlow(book.storageKey).first().isErr)
            } finally { repository.close() }
        }
    }

    @Test fun interruptedPartialTaskDoesNotInventAnExecutorAfterReopening() = runBlocking {
        val source = register(a)
        local.updateBookVolumes(a.bind(source.directory()))
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id)
        val task = downloads.startTask(a, downloads.generation(), id, 2)
        val attempt = downloads.begin(a, downloads.generation(), id)
        val volumes = a.bind(source.directory())
        downloads.target(attempt, volumes, "1", "")
        val chapters = volumes.volumes.flatMap { it.chapters }
        downloads.saveChapter(attempt, SourceChapterId(a, "1").bind(source.body("1")),
            downloadChapterSignature(chapters, 0, "1"), emptyList())
        downloads.taskStage(task, DownloadStage.Body, SourceChapterId(a, "2").storageKey)
        db.close(); openLibrary()
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            val state = awaitTask(repository, a).status!!
            assertEquals(DownloadTaskStatus.Interrupted, state.task.status)
            assertEquals(2, state.task.runAttemptCount)
            assertEquals(2, state.task.chapterIndex)
            assertEquals(BookDownloadState(BookDownloadPhase.Partial, 1, 3), state.content)
            assertTrue(state.task.canResume)
        } finally { repository.close() }
    }

    @Test fun dismissedTaskRejectsLateStagesAndWritesButKeepsSavedContent() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id)
        val task = downloads.startTask(a, downloads.generation(), id, 0)
        val attempt = downloads.begin(a, downloads.generation(), id)
        downloads.dismissTask(a)
        assertTrue(runCatching { downloads.taskStage(task, DownloadStage.Body) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { downloads.finish(attempt, true) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { downloads.begin(a, downloads.generation(), id) }.exceptionOrNull() is CancellationException)
        assertNotNull(chapter(a, "1"))
        db.close(); openLibrary()
        assertTrue(downloads.entry(a)!!.taskHidden)
        val nextId = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), nextId)
        assertFalse(downloads.entry(a)!!.taskHidden)
        assertTrue(runCatching { downloads.startTask(a, downloads.generation(), id, 0) }.exceptionOrNull() is CancellationException)
        downloads.startTask(a, downloads.generation(), nextId, 0)
        val current = downloads.begin(a, downloads.generation(), nextId, requireTask = true)
        assertTrue(runCatching { downloads.begin(a, downloads.generation(), id, requireTask = true) }.exceptionOrNull() is CancellationException)
        downloads.finish(current, success = true)
        assertEquals(3, source.chapterCalls.size)
    }

    @Test fun clearingCompletedTaskHistoryDoesNotDeleteContentOrRestoreCards() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            assertEquals(1f, awaitTask(repository, a).progress)
            repository.clearCompleted()
            withTimeout(5000) { while (downloads.entry(a)?.taskHidden != true) delay(10) }
        } finally { repository.close() }
        db.close(); openLibrary()
        assertNotNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertTrue(downloads.entries().none { !it.taskHidden && it.bookId == a.storageKey })
    }

    @Test fun clearingAStaleCompletedViewCannotHideAnAlreadyQueuedRetry() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            val item = awaitTask(repository, a) as MutableDownloadItem
            downloads.queueTask(a, downloads.generation(), java.util.UUID.randomUUID().toString())
            withTimeout(5000) { while (item.status?.task?.status != DownloadTaskStatus.Interrupted) delay(10) }
            item.progress = 1f // Simulate an old completed frame immediately after explicit retry.
            repository.clearCompleted()
            assertTrue(repository.downloadItemIdList.contains(item))
            assertFalse(downloads.entry(a)!!.taskHidden)
        } finally { repository.close() }
    }

    @Test fun identityPromotionTransfersTheTaskWithoutKeepingTheOldAttempt() = runBlocking {
        val source = register(a)
        val canonical = SourceBookId(a.sourceId, "series")
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id)
        val task = downloads.startTask(a, downloads.generation(), id, 0)
        downloads.begin(a, downloads.generation(), id)
        downloads.mergeIdentity(a, canonical, canonical.bind(BookVolumes(canonical.remoteId, source.directory().volumes)), commit = {})
        assertNull(downloads.entry(a))
        assertEquals(id, downloads.entry(canonical)!!.taskWorkId)
        assertEquals("", downloads.entry(canonical)!!.attempt)
        assertTrue(runCatching { downloads.taskStage(task, DownloadStage.Body) }.exceptionOrNull() is CancellationException)
        downloads.taskStage(task.copy(book = canonical), DownloadStage.Directory)
    }

    @Test fun identityPromotionKeepsImagesReadableThroughTheOldChapterIdentity() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(download() is ListenableWorker.Result.Success)
        val image = SourceImage(a, IMAGE, chapterId = SourceChapterId(a, "1").storageKey)
        val previous = downloads.image(image)!!
        val canonical = SourceBookId(a.sourceId, "series")
        val volumes = canonical.bind(BookVolumes(canonical.remoteId, source.directory().volumes))
        downloads.mergeIdentity(a, canonical, volumes) {
            local.aliases.merge(a, canonical, canonical.bind(source.information()), volumes)
        }
        val migrated = downloads.image(image)!!
        assertNotEquals(previous.path, migrated.path)
        assertArrayEquals(png, migrated.readBytes())
    }

    @Test fun imageVerificationKeepsItsCategoryInsteadOfBecomingANetworkError() = runBlocking {
        register(a).apply {
            withImages = true; imageFailed = true
            imageFailureKind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired
        }
        assertTrue(download() is ListenableWorker.Result.Failure)
        val state = books.downloadStatusFlow(a.storageKey).first()
        assertEquals(DownloadFailure.Verification, state.task.failure)
        assertEquals(DownloadStage.Image, state.task.stage)
        assertEquals(1, state.task.chapterIndex)
        assertEquals(0, state.content.savedChapters)
    }

    @Test fun storageFailureIsRecordedWithoutPublishingItsPrivateExceptionText() = runBlocking {
        register(a)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_download BEFORE INSERT ON chapter_content BEGIN SELECT RAISE(FAIL, 'private-token-fixture'); END")
        assertTrue(download() is ListenableWorker.Result.Failure)
        val state = books.downloadStatusFlow(a.storageKey).first()
        assertEquals(DownloadFailure.Storage, state.task.failure)
        assertEquals(DownloadStage.Storage, state.task.stage)
        assertFalse(downloads.entry(a).toString().contains("private-token-fixture"))
        assertNull(chapter(a, "1"))
    }

    @Test fun exportPreparationDoesNotCreateAnUnrequestedCacheTask() = runBlocking {
        register(a)
        assertTrue(export(images = false) is ListenableWorker.Result.Success)
        assertEquals(DownloadTaskStatus.None.name, downloads.entry(a)!!.taskStatus)
        downloads.queueTask(b, downloads.generation(), java.util.UUID.randomUUID().toString())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            awaitTask(repository, b)
            assertFalse(repository.downloadItemIdList.any { it.type == DownloadType.CACHE && it.bookId == a.storageKey })
            assertNotNull(chapter(a, "1"))
        } finally { repository.close() }
    }

    @Test fun restoredTasksNeverAttachToTheBackedUpExecutor() = runBlocking {
        for (status in listOf(DownloadTaskStatus.Running, DownloadTaskStatus.WaitingRetry)) {
            downloads.clearDownloads()
            val owner = indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(a.storageKey, phase = "updating",
                attempt = "old-attempt", taskWorkId = java.util.UUID.randomUUID().toString(), taskStatus = status.name,
                taskRetryCount = 2, taskNextAttemptAt = Long.MAX_VALUE)
            downloads.restore(listOf(owner), emptyList(), legacy = false)
            val restored = downloads.entry(a)!!
            assertEquals("", restored.attempt)
            assertEquals("", restored.taskWorkId)
            assertEquals(DownloadTaskStatus.Interrupted, restored.taskState(null).status)
            assertEquals(2, restored.taskRetryCount)
            assertEquals(0L, restored.taskNextAttemptAt)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.removeCheckpointSchema() {
        execSQL("ALTER TABLE downloaded_chapter RENAME TO checkpoint_chapters")
        execSQL("CREATE TABLE downloaded_chapter (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, signature TEXT NOT NULL, images TEXT NOT NULL)")
        execSQL("INSERT INTO downloaded_chapter SELECT id, bookId, signature, images FROM checkpoint_chapters")
        execSQL("DROP TABLE checkpoint_chapters")
        execSQL("CREATE INDEX index_downloaded_chapter_bookId ON downloaded_chapter (bookId)")
        execSQL("DROP TABLE download_chapter_candidate")
    }

    @Test fun room25UpgradePreservesTaskOwnershipAndContentWithAnUnusedRetryBudget() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val before = downloads.entry(a)!!
        db.openHelper.writableDatabase.apply {
            execSQL("ALTER TABLE book_download RENAME TO old_download")
            execSQL("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL, taskWorkId TEXT NOT NULL DEFAULT '', taskStatus TEXT NOT NULL DEFAULT 'None', taskStage TEXT NOT NULL DEFAULT 'Unknown', taskChapter TEXT NOT NULL DEFAULT '', taskError TEXT NOT NULL DEFAULT '', taskRunAttempt INTEGER NOT NULL DEFAULT 0, taskHidden INTEGER NOT NULL DEFAULT 0)")
            execSQL("INSERT INTO book_download SELECT bookId, revision, directoryHash, phase, generation, attempt, coverUri, taskWorkId, taskStatus, taskStage, taskChapter, taskError, taskRunAttempt, taskHidden FROM old_download")
            execSQL("DROP TABLE old_download")
            removeCheckpointSchema()
            version = 25
        }
        db.close(); openLibrary()
        val owner = downloads.entry(a)!!
        assertEquals(before.taskWorkId, owner.taskWorkId)
        assertEquals(before.taskStatus, owner.taskStatus)
        assertEquals(0, owner.taskRetryCount)
        assertEquals(0L, owner.taskNextAttemptAt)
        assertEquals(-1L, owner.taskAccountGeneration)
        assertTrue(db.bookDownloadDao().chapters(a.storageKey).all { it.resourceVersion.isEmpty() })
        assertNotNull(chapter(a, "1"))
        assertEquals(3, db.bookDownloadDao().chapters(a.storageKey).size)
    }

    @Test fun room24UpgradeKeepsContentAndDoesNotInventAnActiveTask() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        db.openHelper.writableDatabase.apply {
            execSQL("ALTER TABLE book_download RENAME TO old_download")
            execSQL("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL)")
            execSQL("INSERT INTO book_download SELECT bookId, revision, directoryHash, 'updating', generation, 'old-attempt', coverUri FROM old_download")
            execSQL("DROP TABLE old_download")
            removeCheckpointSchema()
            version = 24
        }
        db.close(); openLibrary()
        val owner = downloads.entry(a)!!
        assertEquals(27, db.openHelper.writableDatabase.version)
        assertEquals("", owner.taskWorkId)
        assertEquals(DownloadTaskStatus.Interrupted, owner.taskState(null).status)
        assertEquals(DownloadStage.Unknown, owner.taskState(null).stage)
        assertNotNull(chapter(a, "1"))
        assertEquals(3, db.bookDownloadDao().chapters(a.storageKey).size)
    }

    private suspend fun awaitTask(repository: DownloadProgressRepository, book: SourceBookId): DownloadItem = withTimeout(5000) {
        while (true) {
            repository.downloadItemIdList.firstOrNull { it.bookId == book.storageKey && it.status != null }?.let { return@withTimeout it }
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private suspend fun export(images: Boolean = true, selected: List<String>? = null,
                               beforeWrite: () -> Unit = {}): ListenableWorker.Result {
        io.mockk.mockkObject(indi.renakoni.nextvol.data.work.EpubShareFiles)
        every { indi.renakoni.nextvol.data.work.EpubShareFiles.publish(context, any(), any(), any()) } answers {
            beforeWrite()
            callOriginal()
        }
        try {
            return indi.renakoni.nextvol.data.work.ExportBookToEPUBWork(context,
                workerParameters(workDataOf("bookId" to a.storageKey, "exportType" to if (selected == null) "BOOK" else "VOLUMES",
                    "selectedVolume" to selected?.joinToString(",").orEmpty(), "includeImages" to images,
                    "downloadGeneration" to downloads.generation())),
                ExportBookToEpubUseCase(context, books, progress, decoder, downloads)).doWork()
        } finally {
            io.mockk.unmockkObject(indi.renakoni.nextvol.data.work.EpubShareFiles)
        }
    }

    @Test fun exportCachesBeforePublishingAndReusesOfflineContentAfterReopening() = runBlocking {
        val source = register(a).apply { withImages = true }
        var published = false
        assertTrue(export(beforeWrite = {
            runBlocking { assertEquals(BookDownloadPhase.Complete, state().phase) }
            published = true
        }) is ListenableWorker.Result.Success)
        assertTrue(published)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        downloads.clearReadingCache()
        registry.unregister(a.sourceId)
        loader.shutdown(); db.close(); openLibrary(); openImages()
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(1, source.imageCalls)
    }

    @Test fun unversionedLegacyDownloadStillExportsOffline() = runBlocking {
        val source = register(a)
        local.updateBookInformation(a.bind(source.information()))
        local.updateBookVolumes(a.bind(source.directory()))
        source.chapters.forEach { info ->
            local.updateChapterContent(SourceChapterId(a, info.id).bind(source.body(info.id)))
        }
        db.userDataDao().insert(UserDataPath.CompletedDownloadBookList.path, "fixture", "CompletedDownloadItemList", "CACHE|${a.storageKey}")
        downloads.prepare()
        registry.unregister(a.sourceId)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertTrue(source.chapterCalls.isEmpty())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun exportRetainsOriginalBytesWhenTheReadingDiskCacheWasEvicted() = runBlocking {
        val source = register(a).apply { withImages = true }
        val image = loader.execute(ImageRequest.Builder(context).data(SourceImage(a, IMAGE)).build()) as SuccessResult
        assertNotNull(image.memoryCacheKey)
        assertNotNull(loader.memoryCache!![image.memoryCacheKey!!])
        cache.clear()
        assertTrue(export() is ListenableWorker.Result.Success)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(2, source.imageCalls)
    }

    @Test fun selectedExportPinsOnlySelectedChaptersAndTextOnlyDoesNotClaimImagesAreCached() = runBlocking {
        val source = register(a).apply {
            withImages = true
            exportVolumes = listOf(Volume("first", "First", chapters.take(1)), Volume("rest", "Rest", chapters.drop(1)))
        }
        assertTrue(export(images = false, selected = listOf(BookIdentity.volumeKey(a, "rest"))) is ListenableWorker.Result.Success)
        assertEquals(mapOf("2" to 1, "3" to 1), source.chapterCalls)
        assertNull(chapter(a, "1"))
        assertEquals(0, source.imageCalls)
        assertEquals(BookDownloadPhase.Partial, state().phase)
        downloads.clearReadingCache()
        assertNotNull(chapter(a, "2"))
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(1, source.imageCalls)
    }

    @Test fun failedSharePublicationKeepsCompletedOfflineDownload() = runBlocking {
        register(a).withImages = true
        val result = export(beforeWrite = { throw java.io.IOException("destination full") }) as ListenableWorker.Result.Failure
        assertEquals("share_failed", result.outputData.getString("reason"))
        assertEquals(BookDownloadPhase.Complete, state().phase)
        downloads.clearReadingCache()
        registry.unregister(a.sourceId)
        assertTrue(export() is ListenableWorker.Result.Success)
    }

    @Test fun clearingDownloadsDuringExportRejectsLateChapterWrites() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val work = async { export() }
        withTimeout(5000) { entered.await() }
        downloads.clearDownloads()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { work.await() } is ListenableWorker.Result.Failure)
        assertNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.None, state().phase)
    }

    @Test fun exportRefreshesDownloadedChaptersAfterSourceRevisionChanges() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 2, "2" to 2, "3" to 2), source.chapterCalls)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun failedRevisionImageRefreshIsRetriedEvenAfterTargetVersionWasWritten() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        source.imageFailed = true
        assertTrue(export() is ListenableWorker.Result.Failure)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        val callsAfterFailure = source.imageCalls
        source.imageFailed = false
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(callsAfterFailure + 1, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun textOnlyRevisionUpdateLeavesOldImagesPendingForLaterExport() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertTrue(export(images = false) is ListenableWorker.Result.Success)
        assertEquals(1, source.imageCalls)
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun cacheAndExportOfSameBookDoNotReplaceEachOthersAttempt() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val caching = async { download() }
        withTimeout(5000) { entered.await() }
        val exporting = async { export() }
        yield()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { caching.await() } is ListenableWorker.Result.Success)
        assertTrue(withTimeout(5000) { exporting.await() } is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    private inner class Remote(private val book: SourceBookId) : WebBookDataSource by EmptyWebDataSource, SourceImageProvider {
        override val id = book.sourceId
        override val cache = Cache(timeout = 60_000)
        var chapters = (1..3).map { ChapterInformation(it.toString(), "Chapter $it") }
        var volumeId = "volume"
        var exportVolumes: List<Volume>? = null
        var withImages = false
        var imageFailed = false
        var extraImage = false
        var failedImage: String? = null
        var imageBytes = png
        var imageFailureKind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.Other
        var directoryFailed = false
        var informationFailed = false
        var failedChapter: String? = null
        var chapterPause: (suspend () -> Unit)? = null
        var directoryCalls = 0
        var imageCalls = 0
        val chapterCalls = mutableMapOf<String, Int>()
        fun directory() = BookVolumes(book.remoteId, exportVolumes ?: listOf(Volume(volumeId, "Volume", chapters)))
        fun information() = BookInformation(book.remoteId, "Book", author = "Author", description = "",
            publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 15, 0, 0), isComplete = false)
        fun body(id: String) = ChapterContent(id, chapters.single { it.id == id }.title,
            ContentBuilder().simpleText("${book.sourceId.id}:$id").apply {
                if (withImages) image(Uri.parse(IMAGE))
                if (withImages && extraImage) image(Uri.parse("$IMAGE?extra"))
            }.build())
        override suspend fun getBookInformation(id: String): com.github.michaelbull.result.Result<BookInformation, WebRequestError> =
            if (informationFailed) Err(WebRequestError("Network", "Unavailable")) else Ok(information())
        override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, WebRequestError> {
            directoryCalls++
            return if (directoryFailed) Err(WebRequestError("Network", "Unavailable")) else Ok(directory())
        }
        override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, WebRequestError> {
            chapterCalls[chapterId] = (chapterCalls[chapterId] ?: 0) + 1
            chapterPause?.invoke()
            return if (failedChapter == chapterId) Err(WebRequestError("Network", "Unavailable")) else Ok(body(chapterId))
        }
        override suspend fun getImage(bookId: String, url: String, cover: Boolean): com.github.michaelbull.result.Result<ByteArray, WebRequestError> {
            imageCalls++
            return if (imageFailed || url == failedImage) Err(WebRequestError("Image", "Unavailable", kind = imageFailureKind)) else Ok(imageBytes)
        }
    }
}
