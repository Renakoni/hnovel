package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentJsonDecoder
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentTestHost
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.image.*
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.*
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.work.*
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import indi.dmzz_yyhyy.lightnovelreader.di.WebDataSourceModule
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipReaderController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ContinuousScrollSettings
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollReaderController
import io.mockk.*
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Path.Companion.toPath
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** Real native parsers and imported rule/worker/broker paths share one Room/Work library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class MixedSourceAcceptanceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun nativeStandardAndExtensionSourcesKeepTheirLibraryAcrossLoginUpdatesAndReconstruction() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(directory.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory.root, "cache").apply { mkdirs() }
        }
        val nativeReads = AtomicInteger()
        // Only the remote-document and periodic reachability edges are replaced. Never send
        // built-in credentials or contact a novel website; all native parsing/providers run.
        mockkConstructor(Wenku8Api::class)
        every { anyConstructed<Wenku8Api>().onLoad() } just Runs
        coEvery { anyConstructed<Wenku8Api>().getWithWenku8Cookie(any()) } coAnswers {
            nativeReads.incrementAndGet()
            Ok(Jsoup.parse(nativeDocument(URI(firstArg<String>()))))
        }
        RuleSourceFixture().use { fixture ->
            fixture.imageBytes = java.io.ByteArrayOutputStream().also {
                android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray().reversedArray()
            val seenCookies = ConcurrentHashMap<String, String>()
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/login") {
                        val user = request.body.readUtf8().substringAfter("user=")
                        return MockResponse().setBody("ok").addHeader("Set-Cookie", "sid=$user; Path=/; Max-Age=3600")
                    }
                    request.getHeader("X-Source")?.let { seenCookies[it] = request.getHeader("Cookie").orEmpty() }
                    return original.dispatch(request)
                }
            }
            val epochs = WebDataSourceModule.provideSourceSessionEpochStore(context)
            var registry = WebSourceRegistry(fixture.authority)
            var accounts = SourceSessionManager(fixture.authority, epochs)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            var login = SourceLoginService(sources, accounts)
            lateinit var db: LightNovelReaderDatabase
            lateinit var local: LocalBookDataSource
            lateinit var books: BookRepository
            lateinit var chapters: ChapterRepository
            lateinit var readingData: BookReadingDataRepository
            lateinit var shelves: BookshelfRepository
            lateinit var stats: StatsRepository
            lateinit var progress: DownloadProgressRepository
            val decoder = ContentJsonDecoder(ContentComponentRegistry())
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker? = when (name) {
                    CacheBookWork::class.java.name -> CacheBookWork(appContext, params, local, progress, books, decoder)
                    ExportBookToEPUBWork::class.java.name -> ExportBookToEPUBWork(appContext, params, books, progress, decoder)
                    CheckUpdateWork::class.java.name -> CheckUpdateWork(appContext, params, books, shelves)
                    else -> null
                }
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
            val work = WorkManager.getInstance(context)
            fun openLibrary() {
                db = Room.databaseBuilder(context, LightNovelReaderDatabase::class.java, File(directory.root, "library.db").absolutePath)
                    .allowMainThreadQueries().build()
                local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao())
                shelves = BookshelfRepository(db.bookshelfDao(), work, registry)
                val text = TextProcessingRepository(mockk { every { enabled } returns false }, mockk { every { enabled } returns false }, ContentComponentRegistry())
                chapters = ChapterRepository(registry, local, text)
                readingData = BookReadingDataRepository(local)
                books = BookRepository(local, shelves, text, work, chapters, readingData, registry)
                stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), books, StatisticsWriteCoordinator())
                progress = DownloadProgressRepository(db.userDataDao(), books)
            }
            openLibrary()
            val cache = coil3.disk.DiskCache.Builder().directory(File(context.filesDir, "images").path.toPath()).maxSizeBytes(1024 * 1024).build()
            fun imageLoader() = coil3.ImageLoader.Builder(context).diskCache(cache).components {
                add(SourceImageInterceptor(registry, context)); add(SourceImageFetcher.Factory())
            }.build()
            var loader = imageLoader()
            coil3.SingletonImageLoader.setUnsafe(loader)
            var native = Wenku8Api()
            fun registerNative() {
                registry.register(native, SourceMetadata(WebDataSourceItem(native.id, "Wenku8", "fixture"),
                    setOf(SourceCapability.Search, SourceCapability.BookInformation, SourceCapability.Directory,
                        SourceCapability.ChapterContent, SourceCapability.Explore, SourceCapability.Categories), builtIn = true))
            }
            suspend fun install(raw: JsonObject, profile: String, file: Boolean = false): Identifier {
                val preview = if (file) {
                    val definition = File(directory.root, "source.json").apply { writeText(raw.toString()) }
                    sources.importer.previewFile(definition.toPath(), profile)
                } else sources.importer.preview(raw.toString(), profile)
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                val saved = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                assertNull(saved.error)
                return sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            }
            suspend fun awaitWork(request: OneTimeWorkRequest): WorkInfo {
                work.enqueue(request).await()
                return withTimeout(30000) { work.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished } }
            }
            try {
                registerNative()
                val a = install(ruleDefinition(fixture, "A", false), LEGADO_PROFILE, file = true)
                val b = install(ruleDefinition(fixture, "B", true), EXTENSION_PROFILE)
                val searchOnly = install(fixture.raw("SearchOnly"), LEGADO_PROFILE)
                assertEquals(setOf(native.id, a, b), registry.sources.value.filter { SourceCapability.Categories in it.metadata.capabilities }.map { it.metadata.id }.toSet())
                val search = ExploreRepository(registry)
                assertTrue(search.open(searchOnly).isOk)
                assertFalse(registry.sources.value.single { it.metadata.id == searchOnly }.metadata.capabilities.contains(SourceCapability.Explore))
                for ((id, user) in listOf(a to "alice", b to "bob")) {
                    val attempt = login.begin(id)
                    val form = login.form(attempt)
                    assertEquals("user", form.fields.first().name)
                    login.submit(attempt, mapOf("user" to user))
                    assertEquals(LoginStatus.Authenticated, login.status(id))
                }
                shelves.addBookshelf(Bookshelf(id = 1, name = "Mixed profiles", systemUpdateReminder = true))
                val owners = listOf(native.id, a, b)
                val identities = mutableListOf<SourceBookId>()
                for ((index, id) in owners.withIndex()) {
                    val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                    val discovery = runtime.discovery!!
                    val category = discovery.categories().get()!!.first { it.target.target.isNotBlank() }
                    val fromCategory = discovery.open(category.target).loadMore().get()!!.books.first().id
                    assertEquals(id, fromCategory.sourceId)
                    assertEquals(id, discovery.feed().get()!!.first { it.books.isNotEmpty() }.books.first().id.sourceId)
                    val query = search.open(id).get()!!
                    val result = query.search(query.types.first(), "Same title").first { it is SearchResult.MultipleBook } as SearchResult.MultipleBook
                    val book = SourceBookId.fromStorageKey(result.bookId)
                    identities += book
                    assertEquals(fromCategory, book)
                    shelves.addBookIntoBookShelf(1, books.getBookInformationFlow(book).last().get()!!)
                    assertEquals(WorkInfo.State.SUCCEEDED, withTimeout(30000) { books.cacheBook(book.storageKey).filterNotNull().first { it.state.isFinished } }.state)
                    val chapter = local.getBookVolumes(book.storageKey)!!.volumes.single().chapters.first()
                    books.updateUserReadingData(book.storageKey) { it.copyWithUpdatedChapterReadingProgress(chapter.id, (index + 1) / 4f).copy(lastReadChapterId = chapter.id) }
                    stats.updateReadingStatistics(ReadingStatsUpdate(book.storageKey, secondDelta = (index + 1) * 60, readEventDelta = 1, localTime = LocalTime.NOON))
                    val output = File(directory.root, "${book.fileKey}.epub")
                    val uri = Uri.parse("content://fixture/${book.fileKey}.epub")
                    org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, output.outputStream())
                    val exported = awaitWork(OneTimeWorkRequestBuilder<ExportBookToEPUBWork>().setInputData(workDataOf(
                        "bookId" to book.storageKey, "exportType" to "BOOK", "uri" to uri.toString())).build())
                    assertEquals(exported.outputData.toString(), WorkInfo.State.SUCCEEDED, exported.state)
                    ZipFile(output).use { zip ->
                        val text = zip.entries().asSequence().filter { it.name.endsWith(".xhtml") }
                            .joinToString { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                        assertTrue(text.contains(listOf("Wenku8 first", "A first", "B first")[index]))
                    }
                }
                assertEquals(identities[1].remoteId, identities[2].remoteId)
                assertEquals("sid=alice", seenCookies["A"]); assertEquals("sid=bob", seenCookies["B"])
                assertEquals(3, identities.map { it.storageKey }.distinct().size)
                assertEquals(6, stats.getTotalReadingSummary().totalMinutes)
                assertEquals(identities.map { it.storageKey }.toSet(), shelves.getBookshelf(1)!!.allBookIds.toSet())

                val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
                val prior = sources.installedSources().single { ImportedRuleSources.id(it.definition) == a }.definition
                val changed = JsonObject(ruleDefinition(fixture, "A", false) + ("bookSourceName" to JsonPrimitive("Updated name")))
                val preview = sources.importer.preview(changed.toString(), LEGADO_PROFILE)
                val candidate = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Replace(prior.reference())))).items.single().reference!!
                val oldRuntime = (registry.resolve(a) as SourceResolution.Ready).runtime
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                updates.apply(a, candidate, grants)
                assertFalse(oldRuntime.isAvailable)
                updates.rollback(a, grants)
                assertEquals(prior.contentDigest, (registry.resolve(a) as SourceResolution.Ready).runtime.metadata.revision)
                assertEquals(LoginStatus.Authenticated, login.status(a))
                val diagnostics = SourceDiagnostics(context, sources, fixture.runner, fixture.authority, accounts, registry, StorageCipher.Plain)
                val report = diagnostics.run(b, DiagnosticStage.LoginForm, "", "", "")
                assertEquals("Success", report.result)
                assertEquals(EXTENSION_PROFILE, report.profile)
                assertFalse(report.export().contains("sid=bob"))
                assertFalse(report.export().contains(fixture.server.url("/").toString()))

                fixture.extraChapter = true
                val update = awaitWork(OneTimeWorkRequestBuilder<CheckUpdateWork>().build())
                assertEquals(WorkInfo.State.SUCCEEDED, update.state)
                assertEquals(2, update.outputData.getInt("updatedCount", -1))
                assertEquals(0, update.outputData.getInt("failedCount", -1))
                val entered = CompletableDeferred<Unit>()
                fixture.afterRun = { entered.complete(Unit); awaitCancellation() }
                val pending = async { books.refreshBookInformation(identities[1]) }
                withTimeout(10000) { entered.await() }
                login.logout(a)
                assertEquals(WebRequestErrorKind.SourceUnavailable, withTimeout(10000) { pending.await() }.getError()!!.kind)
                fixture.afterRun = {}
                assertEquals(LoginStatus.LoggedOut, login.status(a))
                assertEquals(LoginStatus.Authenticated, login.status(b))
                assertEquals(StorageResult.Value("note-alice"), sources.loginTarget(a).session.read(StorageRequest(StorageArea.Config, "value:note")))
                assertEquals("", sources.loginTarget(a).session.cookie(fixture.server.url("/").toString()))
                loader.memoryCache?.clear()
                val image = loader.execute(coil3.request.ImageRequest.Builder(context).data(SourceImage(identities[1], fixture.server.url("/image.png").toString())).build())
                assertTrue(image.toString(), image is coil3.request.SuccessResult)
                assertEquals(coil3.decode.DataSource.DISK, (image as coil3.request.SuccessResult).dataSource)

                val generation = accounts.current(a).generation
                sources.stop(); registry.unregister(native.id); native.close(); db.close(); loader.shutdown()
                registry = WebSourceRegistry(fixture.authority)
                accounts = SourceSessionManager(fixture.authority, epochs)
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                login = SourceLoginService(sources, accounts)
                sources.restore(); native = Wenku8Api(); registerNative(); openLibrary()
                loader = imageLoader(); coil3.SingletonImageLoader.setUnsafe(loader)
                assertEquals(generation, accounts.current(a).generation)
                assertEquals(LoginStatus.Authenticated, login.status(b))
                assertEquals(prior.contentDigest, sources.installedSources().single { ImportedRuleSources.id(it.definition) == a }.definition.contentDigest)
                for (id in listOf(a, b, searchOnly)) sources.remove(id)
                registry.unregister(native.id)
                val requests = fixture.documents.get(); val nativeRequests = nativeReads.get()
                val rendering = ContentTestHost().apply { initializeInjector() }
                val chapterLoader = ReaderChapterLoader(chapters, rendering.renderer)
                for ((index, book) in identities.withIndex()) {
                    val reading = books.getUserReadingData(book.storageKey)
                    assertEquals((index + 1) / 4f, reading.currentChapterReadingProgressMap[reading.lastReadChapterId])
                    val content = books.getChapterContentFlow(requireNotNull(reading.lastReadChapterId), book.storageKey).last().get()!!
                    assertTrue(content.content.toString().contains(listOf("Wenku8 first", "A first", "B first")[index]))
                    for (flip in listOf(true, false)) {
                        // Controller failures belong to this test, including during teardown.
                        val modeScope = CoroutineScope(Job(coroutineContext.job) + Dispatchers.Default)
                        val mode: ReaderModeController = if (flip) FlipReaderController(chapterLoader, readingData, modeScope, { _, _ -> })
                            else ScrollReaderController(chapterLoader, readingData, modeScope, object : ContinuousScrollSettings {
                                override fun getFlow() = flowOf(false)
                                override suspend fun isEnabled() = false
                            }, { _, _ -> }, mainDispatcher = Dispatchers.Default)
                        try {
                            mode.changeBookId(book.storageKey)
                            mode.changeChapter(requireNotNull(reading.lastReadChapterId))
                            withTimeout(10000) { while (mode.uiState.readingChapterContent?.get() == null) delay(10) }
                            val displayed = mode.uiState.readingChapterContent!!.get()!!
                            assertEquals(reading.lastReadChapterId, displayed.id)
                            assertEquals(book.storageKey, mode.uiState.bookId)
                            val text = displayed.content.mapNotNull { it.data as? SimpleTextComponentData }.joinToString { it.text }
                            assertTrue(text, text.contains(listOf("Wenku8 first", "A first", "B first")[index]))
                        } finally { mode.close(); modeScope.coroutineContext.job.cancelAndJoin() }
                    }
                }
                assertEquals(6, stats.getTotalReadingSummary().totalMinutes)
                assertEquals(setOf(60, 120, 180), db.bookRecordDao().getAllBookRecords().map { it.seconds }.toSet())
                assertEquals(requests, fixture.documents.get()); assertEquals(nativeRequests, nativeReads.get())
            } finally {
                fixture.afterRun = {}; sources.stop(); registry.unregister(native.id); native.close()
                work.cancelAllWork().await(); WorkManagerTestInitHelper.closeWorkDatabase(); db.close()
                loader.shutdown(); cache.shutdown(); coil3.SingletonImageLoader.reset(); unmockkConstructor(Wenku8Api::class)
            }
        }
    }

    private fun ruleDefinition(fixture: RuleSourceFixture, label: String, extension: Boolean): JsonObject {
        val base = fixture.raw(label)
        return JsonObject(base + mapOf(
            "loginUrl" to JsonPrimitive("""function fields(){return [{name:'user'}];}
                function login(){var user=source.getLoginInfoMap().get('user');var response=java.post(baseUrl.substring(0,baseUrl.lastIndexOf('/')+1)+'login','user='+user,{'Content-Type':'application/x-www-form-urlencoded'});if(response.statusCode()!==200)throw 'login failed';source.put('note','note-'+user);}"""),
            "loginUi" to JsonPrimitive(if (extension) "@js:JSON.stringify(fields())" else "[{\"name\":\"user\"}]"),
            "exploreUrl" to JsonPrimitive(if (extension) "@js:infoMap.region='all';infoMap.save();[{id:'all',title:'All',url:'/search'}]" else "All::/search"),
            "ruleExplore" to base.getValue("ruleSearch")
        ))
    }

    private fun nativeDocument(uri: URI): String {
        val path = uri.path
        return when {
        path.endsWith("tags.php") && uri.query == null -> "<a href='tags.php?t=%D0%A3%D4%B0'>校园</a>"
        path == "/book/123.htm" -> """<div id='content'><div><table>
            <tr><td><table><tr><td><span><b>Same title</b></span></td></tr></table></td></tr>
            <tr><td>文库分类：电击文库</td><td>小说作者：Same author</td><td>已完结</td><td>最后更新：2026-09-10</td><td>全文长度：1000字</td></tr>
            </table><table><tr><td><img src=''></td><td><span><b>作品Tags：校园</b></span><span></span><span></span><span></span><span></span><span>Fixture</span></td></tr></table></div></div>"""
        path.endsWith("index.htm") -> "<table><tr><td class='vcss' vid='1'>Volume</td></tr><tr><td><a href='1.htm'>One</a><a href='2.htm'>Two</a></td></tr></table>"
        path.startsWith("/novel/") -> "<div id='title'>Chapter</div><div id='content'>Wenku8 first</div>"
        path == "" || path == "/" -> "<html></html>"
        path.startsWith("/modules/article/") -> """<div id='content'><table><tr><td><div>
            <div><a href='/book/123.htm' title='Same title'><img src=''></a></div><div><b><a href='/book/123.htm'>Same title</a></b>
            <p>作者:Same author/分类:电击文库</p><p>更新:2026-09-10/字数:1K/已完结</p><p><span>校园</span></p><p>简介:Fixture</p>
            </div></div></td></tr></table></div><div id='pagelink'><em>1/1</em></div>"""
        else -> error("Unexpected native fixture path: $path")
        }
    }
}
