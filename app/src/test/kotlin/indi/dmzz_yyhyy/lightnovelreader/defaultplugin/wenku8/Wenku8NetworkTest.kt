package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentJsonDecoder
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import indi.dmzz_yyhyy.lightnovelreader.data.work.workerParameters
import indi.dmzz_yyhyy.lightnovelreader.data.download.BookDownloadStore
import indi.dmzz_yyhyy.lightnovelreader.data.image.SourceImage
import indi.dmzz_yyhyy.lightnovelreader.data.image.SourceImageFetcher
import indi.dmzz_yyhyy.lightnovelreader.data.image.SourceImageInterceptor
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore.Wenku8Discovery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.image.SourceImageProvider
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** Real Ktor/OkHttp and Coil calls, with DNS and sockets redirected only inside this fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8NetworkTest {
    @get:Rule val directory = TemporaryFolder()
    private val a = Identifier("routing-fixture", "a")
    private val b = Identifier("routing-fixture", "b")
    private val url = "http://www.wenku8.cc/document"

    private inner class Fixture : AutoCloseable {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        val proxySelections = AtomicInteger()
        private val previousProxy = ProxySelector.getDefault()
        val firstSockets = RoutedSockets(first)
        val secondSockets = RoutedSockets(second)
        val firstDns = AtomicInteger()
        val secondDns = AtomicInteger()
        val default = route(SourceNetworkMode.SystemDefault, firstSockets, firstDns)
        val bypass = route(SourceNetworkMode.BypassVpn, secondSockets, secondDns)
        val routes = ConcurrentHashMap(mapOf(a to default, b to bypass))
        val api = Wenku8Api { routes.getValue(it) }
        val registry = WebSourceRegistry()
        val firstPng = png(0xffff0000.toInt())
        val secondPng = png(0xff0000ff.toInt())

        init {
            // This is a JVM-local selector, not an Android or host proxy setting.
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> { proxySelections.incrementAndGet(); return listOf(Proxy.NO_PROXY) }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            first.dispatcher = replies("A", firstPng)
            second.dispatcher = replies("B", secondPng)
        }

        fun source(owner: Identifier): WebBookDataSource = object : WebBookDataSource by EmptyWebDataSource,
            SourceImageProvider by api {
            override val id = owner
            override suspend fun getBookInformation(id: String) = api.getWithWenku8Cookie(url)
                .map { BookInformation(id, it.text(), coverUri = Uri.parse("http://www.wenku8.cc/image"), author = "Fixture",
                    description = "", publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.MIN, isComplete = false) }
                .mapError { WebRequestError("Request failed", "Fixture request failed", it) }
            override suspend fun getBookVolumes(id: String) = api.getWithWenku8Cookie("http://www.wenku8.cc/directory")
                .map { BookVolumes(id, listOf(Volume("volume", "Volume", listOf(ChapterInformation("chapter", "Chapter"))))) }
                .mapError { WebRequestError("Request failed", "Fixture request failed", it) }
            override suspend fun getChapterContent(chapterId: String, bookId: String) = api.getWithWenku8Cookie("http://www.wenku8.cc/chapter")
                .map { ChapterContent(chapterId, "Chapter", ContentBuilder().simpleText(it.text())
                    .image(Uri.parse("http://www.wenku8.cc/image")).build()) }
                .mapError { WebRequestError("Request failed", "Fixture request failed", it) }
            override val searchProvider = object : SearchProvider {
                override val searchTypes = listOf(SearchType("all", LocalString("All"), LocalString("Search")))
                override fun search(searchType: SearchType, keyword: String) = flow {
                    api.getWithWenku8Cookie("http://www.wenku8.cc/search").getOrElse { throw it }
                    emit(SearchResult.End())
                }
            }
            override val discoveryProvider = Wenku8Discovery("http://www.wenku8.cc") { address ->
                api.getWithWenku8Cookie(address).getOrElse { throw it }
            }
        }

        suspend fun runtime(owner: Identifier): SourceRuntime {
            registry.register(source(owner), SourceMetadata(WebDataSourceItem(owner, "Fixture", "fixture"),
                setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent,
                    SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Images)))
            return (registry.resolve(owner) as SourceResolution.Ready).runtime
        }

        fun route(mode: SourceNetworkMode, sockets: RoutedSockets, lookups: AtomicInteger) =
            SourceNetworkRoute(mode, Dns { hostname ->
                check(hostname == "www.wenku8.cc" || hostname.endsWith(".fixture")) { "Unexpected fixture DNS request" }
                lookups.incrementAndGet()
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(203.toByte(), 0, 113, 7)))
            }, sockets)

        override fun close() {
            registry.sources.value.forEach { registry.unregister(it.metadata.id) }
            api.close(); default.invalidate(); bypass.invalidate()
            ProxySelector.setDefault(previousProxy)
            first.shutdown(); second.shutdown()
        }
    }

    private class RoutedSockets(private val server: MockWebServer) : SocketFactory() {
        val created = AtomicInteger()
        // Model Network.socketFactory: a selected physical socket must not consult JVM SOCKS settings.
        override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                check((endpoint as InetSocketAddress).address.hostAddress == "203.0.113.7")
                created.incrementAndGet()
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = error("Expected an unconnected socket")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Expected an unconnected socket")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Expected an unconnected socket")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Expected an unconnected socket")
    }

    private fun document(text: String) = MockResponse().setHeader("Content-Type", "text/html; charset=GBK")
        .setBody(Buffer().write("<p>$text • ・ 〜</p>".toByteArray(Charset.forName("GB18030"))))
    private fun png(color: Int) = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.PNG, 100, output)
    }.toByteArray()
    private fun replies(label: String, image: ByteArray) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = if (request.path == "/image")
            MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
        else document(label)
    }

    @Test(timeout = 30000) fun sharedApiUsesActualOwnerForCoalescedBodiesSearchDiscoveryAndCoilImages() = runBlocking {
        Fixture().use { fixture ->
            val first = fixture.runtime(a)
            val second = fixture.runtime(b)
            val titles = awaitAll(async { first.getBookInformation("same").getOrElse { error(it.title) }.title },
                async { second.getBookInformation("same").getOrElse { error(it.title) }.title })
            assertEquals(listOf("A • ・ 〜", "B • ・ 〜"), titles)
            first.search.search(first.search.searchTypes.first(), "query").toList()
            second.search.search(second.search.searchTypes.first(), "query").toList()
            first.discovery!!.open(SourceDiscoveryTarget(a, "allBook")).loadMore().getOrElse { error(it.toString()) }
            second.discovery!!.open(SourceDiscoveryTarget(b, "allBook")).loadMore().getOrElse { error(it.toString()) }
            assertEquals(3, fixture.first.requestCount); assertEquals(3, fixture.second.requestCount)
            repeat(3) {
                val request = fixture.first.takeRequest(3, TimeUnit.SECONDS)!!
                assertTrue(request.getHeader("User-Agent").orEmpty().contains("Chrome/125.0.0.0"))
                assertFalse("Document cookie must be preserved", request.getHeader("Cookie").isNullOrEmpty())
            }
            val context = RuntimeEnvironment.getApplication()
            val downloads = mockk<BookDownloadStore> { coEvery { image(any()) } returns null }
            val loader = ImageLoader.Builder(context).diskCache(null).components {
                add(SourceImageInterceptor(fixture.registry, context, downloads)); add(SourceImageFetcher.Factory())
            }.build()
            suspend fun load(owner: Identifier) = loader.execute(ImageRequest.Builder(context)
                .data(SourceImage(SourceBookId(owner, "same"), "http://www.wenku8.cc/image")).build())
            try {
                assertTrue(load(a) is SuccessResult); assertTrue(load(b) is SuccessResult)
                assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
                assertEquals(4, fixture.first.requestCount); assertEquals(4, fixture.second.requestCount)
                assertNull("Document cookies must not become image headers", fixture.first.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertArrayEquals(fixture.firstPng, first.imageBytes("same", "http://www.wenku8.cc/image", false).getOrElse { error(it.title) })
                assertArrayEquals(fixture.secondPng, second.imageBytes("same", "http://www.wenku8.cc/image", true).getOrElse { error(it.title) })
                val cachedCount = fixture.first.requestCount + fixture.second.requestCount
                fixture.routes[a] = fixture.bypass
                assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
                assertEquals(cachedCount, fixture.first.requestCount + fixture.second.requestCount)
            } finally { loader.shutdown() }
            assertTrue(fixture.firstDns.get() > 0); assertTrue(fixture.secondDns.get() > 0)
            assertTrue(fixture.firstSockets.created.get() > 0); assertTrue(fixture.secondSockets.created.get() > 0)
        }
    }

    @Test(timeout = 45000) fun retriesAndRedirectsRetainTheirRouteWhileNewRequestsReadTheSavedMode() = runBlocking {
        Fixture().use { fixture ->
            val runtime = fixture.runtime(a)
            val requests = AtomicInteger()
            fixture.first.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fixture.routes[a] = fixture.bypass
                    return when {
                        request.path == "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location", "http://child.fixture/final")
                        request.path == "/document" && requests.getAndIncrement() == 0 -> MockResponse().setResponseCode(503)
                        else -> document("A")
                    }
                }
            }
            assertEquals("A • ・ 〜", runtime.getBookInformation("same").getOrElse { error(it.title) }.title)
            assertEquals(0, fixture.second.requestCount)
            assertEquals("B • ・ 〜", runtime.getBookInformation("new").getOrElse { error(it.title) }.title)
            val nextRequests = fixture.second.requestCount
            fixture.routes[a] = fixture.default
            assertEquals("A • ・ 〜", runtime.execute {
                fixture.api.getWithWenku8Cookie("http://www.wenku8.cc/redirect").getOrElse { throw it }.text()
            })
            assertEquals(nextRequests, fixture.second.requestCount)
        }
    }

    @Test(timeout = 15000) fun bypassIgnoresTheJvmProxySelectorAndUnavailableRoutesNeverFallBack() = runBlocking {
        Fixture().use { fixture ->
            val runtime = fixture.runtime(b)
            assertTrue(runtime.getBookInformation("same").isOk)
            assertEquals(0, fixture.proxySelections.get())
            fixture.bypass.invalidate()
            val before = fixture.second.requestCount
            val body = runtime.getBookInformation("uncached")
            assertTrue(body.component2()?.throwable is Wenku8RouteUnavailableException)
            assertTrue(runtime.imageBytes("same", "http://www.wenku8.cc/image", true).component2()?.throwable is Wenku8RouteUnavailableException)
            assertEquals(before, fixture.second.requestCount); assertEquals(0, fixture.first.requestCount)
            val next = fixture.route(SourceNetworkMode.BypassVpn, fixture.secondSockets, fixture.secondDns)
            try {
                fixture.routes[b] = next
                assertEquals("B • ・ 〜", runtime.getBookInformation("retry").getOrElse { error(it.title) }.title)
            } finally { next.invalidate() }
        }
    }

    @Test(timeout = 15000) fun routeLossCancelsPendingDocumentAndImageCalls() = runBlocking {
        Fixture().use { fixture ->
            val runtime = fixture.runtime(b)
            fixture.second.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val body = async { runtime.getBookInformation("body") }
            val image = async { runtime.imageBytes("same", "http://www.wenku8.cc/image", false) }
            withContext(Dispatchers.IO) { repeat(2) { assertNotNull(fixture.second.takeRequest(3, TimeUnit.SECONDS)) } }
            fixture.bypass.invalidate()
            withTimeout(3000) {
                assertTrue(body.await().component2()?.throwable is Wenku8RouteUnavailableException)
                assertTrue(image.await().component2()?.throwable is Wenku8RouteUnavailableException)
            }
            assertEquals(0, fixture.first.requestCount)
        }
    }

    @Test(timeout = 15000) fun unregisteringOneCallerCancelsItsWorkWithoutClosingAnotherOwnersSharedApi() = runBlocking {
        Fixture().use { fixture ->
            val first = fixture.runtime(a)
            val second = fixture.runtime(b)
            fixture.first.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val pending = async { first.getBookInformation("body") }
            withContext(Dispatchers.IO) { assertNotNull(fixture.first.takeRequest(3, TimeUnit.SECONDS)) }
            fixture.registry.unregister(a)
            withTimeout(3000) { pending.join() }
            assertTrue(pending.isCancelled)
            assertEquals("B • ・ 〜", second.getBookInformation("body").getOrElse { error(it.title) }.title)
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test(timeout = 30000) fun backgroundDownloadsKeepBodiesAndPicturesOnTheOwningSourcesRoute() = runBlocking {
        Fixture().use { fixture ->
            fixture.runtime(a); fixture.runtime(b)
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir(): File = directory.root.resolve("files").apply { mkdirs() }
            }
            val db = Room.inMemoryDatabaseBuilder(context, LightNovelReaderDatabase::class.java).allowMainThreadQueries().build()
            val local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao())
            val downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
            val shelves = BookshelfRepository(db.bookshelfDao(), mockk(relaxed = true), fixture.registry, downloads)
            val text = TextProcessingRepository(mockk { every { enabled } returns false },
                mockk { every { enabled } returns false }, ContentComponentRegistry())
            val books = BookRepository(local, shelves, text, mockk(relaxed = true),
                ChapterRepository(fixture.registry, local, text), BookReadingDataRepository(local), fixture.registry, downloads)
            val cache = DiskCache.Builder().directory(directory.root.resolve("coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
            val loader = ImageLoader.Builder(context).diskCache(cache).components {
                add(SourceImageInterceptor(fixture.registry, context, downloads)); add(SourceImageFetcher.Factory())
            }.build()
            SingletonImageLoader.setUnsafe(loader)
            val first = SourceBookId(a, "same")
            val second = SourceBookId(b, "same")
            suspend fun download(book: SourceBookId) = CacheBookWork(context, workerParameters(workDataOf(
                "bookId" to book.storageKey, "downloadGeneration" to downloads.generation())), mockk(relaxed = true), books, downloads).doWork()
            try {
                assertEquals(listOf(ListenableWorker.Result.success(), ListenableWorker.Result.success()),
                    awaitAll(async { download(first) }, async { download(second) }))
                downloads.clearReadingCache()
                for ((book, label, expected) in listOf(Triple(first, "A", fixture.firstPng), Triple(second, "B", fixture.secondPng))) {
                    val chapter = local.getChapterContent(SourceChapterId(book, "chapter").storageKey)!!
                    assertTrue(chapter.content.toString().contains(label))
                    for (cover in listOf(true, false)) {
                        val image = SourceImage(book, "http://www.wenku8.cc/image", cover)
                        assertArrayEquals(expected, downloads.image(image)!!.readBytes())
                        assertTrue(loader.execute(ImageRequest.Builder(context).data(image).build()) is SuccessResult)
                    }
                }
            } finally {
                fixture.registry.unregister(a); fixture.registry.unregister(b)
                loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close()
            }
        }
    }
}
