package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.*
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

/** Explicit live-service probe. The public-DNS control is injected into this test's broker only. */
@RunWith(AndroidJUnit4::class)
class ZLibraryLiveInstrumentedTest {
    @Test fun actualSearchPaginationDetailsMirrorAndCoverApproval() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live requests require explicit opt-in", arguments.getString("liveZLibrary") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "zlibrary-live-${UUID.randomUUID()}").apply { mkdirs() }
        val registry = WebSourceRegistry()
        val brokers = mutableListOf<SourceBroker>()
        fun session(name: String, grants: List<NetworkGrant>, dns: Dns = Dns.SYSTEM): hnovel.network.SourceSession =
            SourceBroker(File(directory, name).toPath(), dns = dns,
                limits = BrokerLimits(concurrency = 3, minIntervalMillis = 350)).also { brokers += it }
                .open(SourceScope(ZLibrarySources.ID.namespace, ZLibrarySources.ID.id, "zlibrary-eapi"), grants)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        try { withTimeout(180000) {
            val origin = ZLibrarySources.MIRRORS.first()
            val apiGrant = NetworkGrant(origin)
            val system = runCatching { ZLibraryClient(session("system", listOf(apiGrant)), origin).search("三体", 1, limit = 3) }
            report("Z-Library system DNS: " + (system.exceptionOrNull()?.let {
                (it as? SourceRequestException)?.error?.name ?: it.javaClass.simpleName
            } ?: "${system.getOrThrow().books.size} books"))

            val mappings = Json.parseToJsonElement(String(Base64.decode(arguments.getString("zLibraryDnsBase64"), Base64.DEFAULT), Charsets.UTF_8)).jsonObject
            val dns = Dns { host -> mappings[host]?.jsonArray?.map { InetAddress.getByName(it.jsonPrimitive.content) }
                ?: throw UnknownHostException("No fresh test-control DNS for host") }
            val firstSession = session("api-only", listOf(apiGrant), dns)
            val client = ZLibraryClient(firstSession, origin)
            val source = ZLibrarySource(context, firstSession, client)
            val registration = registry.register(source, ZLibrarySources.METADATA)
            val search = ExploreRepository(registry).open(ZLibrarySources.ID).get()!!
            val results = search.search(search.types.single { it.type == "chinese-epub" }, "三体").take(21).toList()
            assertEquals(21, results.size)
            assertTrue(results.toString(), results.all { it is SearchResult.MultipleBook })
            val books = results.map { SourceBookId.fromStorageKey((it as SearchResult.MultipleBook).bookId) }
            assertEquals(21, books.distinct().size)
            assertTrue(books.all { it.sourceId == ZLibrarySources.ID })
            val runtime = (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime
            val first = books.first()
            val info = runtime.getBookInformation(first.remoteId).get()!!
            assertTrue(info.title.isNotBlank()); assertTrue(info.author.isNotBlank())
            assertTrue(info.tags.containsAll(listOf("Chinese", "EPUB")))
            val cover = info.coverUri.toString()
            val denied = runtime.imageBytes(first.remoteId, cover, true).getError()!!.throwable as SourceRequestException
            assertEquals(DiscoveryError.PermissionDenied, denied.error)
            assertEquals(ResourceKind.Image, denied.denial!!.kind)
            assertTrue(firstSession.deniedOrigins.contains(denied.denial))
            val approved = session("cover-approved", listOf(apiGrant, NetworkGrant(denied.denial!!.origin)), dns)
            registry.replace(registration, ZLibrarySource(context, approved, ZLibraryClient(approved, origin)), ZLibrarySources.METADATA) {}
            assertFalse(runtime.isAvailable)
            val next = (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime
            val bytes = next.imageBytes(first.remoteId, cover, true).get()!!
            assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            val english = ZLibraryClient(approved, origin).search("Alice in Wonderland", 1, "english", "PDF", 3)
            assertEquals(3, english.books.size)
            assertTrue(english.books.all { it.language == "English" && it.format == "PDF" })
            val mirror = ZLibrarySources.MIRRORS[1]
            val mirrored = ZLibraryClient(session("mirror", listOf(NetworkGrant(mirror)), dns), mirror).information(first.remoteId)
            assertEquals(first.remoteId, mirrored.id)
            assertEquals(first.storageKey, SourceBookId(next.id, mirrored.id).storageKey)
            report("Z-Library public-DNS control: 21 distinct source-bound Chinese EPUB books across two pages; 3 English PDF books; " +
                "detail ${first.remoteId}; mirror identity retained; approved ${denied.denial!!.origin} returned ${bytes.size} image bytes. Title: ${info.title}")
        } } finally {
            registry.unregister(ZLibrarySources.ID)
            brokers.forEach { it.close() }
            directory.deleteRecursively()
        }
    }
}
