package indi.renakoni.nextvol.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.NextVolApplication
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Opt-in live metadata checks; ordinary CI/device runs never depend on public sites. */
@RunWith(AndroidJUnit4::class)
class Wenku8SearchDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun live() = assumeTrue(InstrumentationRegistry.getArguments().getString("liveWenku8") == "true")
    private fun report(name: String, content: String) {
        File(instrumentation.targetContext.getExternalFilesDir(null), name).writeText(content)
    }

    @Test fun publicBangumiDomainsOnDevice() = runBlocking {
        live()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
        val lines = coroutineScope {
            listOf("https://bgm.tv/", "https://bangumi.tv/", "https://api.bgm.tv/v0/subjects/19441",
                "https://api.bangumi.tv/v0/subjects/19441", "https://api.bangumi.vip/v0/subjects/19441").map { url -> async(Dispatchers.IO) {
                val started = android.os.SystemClock.elapsedRealtime()
                val status = try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        "${response.code}\t${response.header("Content-Type")}\t${response.header("Location")}"
                    }
                } catch (failure: Exception) { failure.javaClass.simpleName }
                "$url\t$status\t${android.os.SystemClock.elapsedRealtime() - started}ms"
            } }.awaitAll()
        }
        report("wenku8-search-domains.txt", lines.joinToString("\n"))
    }

    @Test fun liveAbbreviationsReachWenku8Books() = runBlocking {
        live()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBangumiRecall") == "true")
        val app = instrumentation.targetContext.applicationContext as NextVolApplication
        val api = Wenku8Api(app.wenku8SearchSupport) { id -> app.sourceNetworkSettings.forSource(id).snapshot() }
        val provider = api.searchProvider as indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8SearchProvider
        val observations = mutableListOf<String>()
        try {
            for ((query, expected) in listOf("春物" to setOf("1213"), "俺妹" to setOf("47"),
                "实教" to setOf("1973"), "魔禁" to setOf("3", "1297", "2728"))) {
                val start = android.os.SystemClock.elapsedRealtime()
                val ids = linkedSetOf<String>()
                withTimeout(35_000) {
                    provider.searchPageUpdates(provider.searchTypes.first(), query, 1, "device-$query").collect { batch ->
                        batch.books.forEach { if (ids.add(it.bookId)) observations +=
                            "$query\t${it.bookId}\t${it.information?.title.orEmpty()}\t${android.os.SystemClock.elapsedRealtime() - start}ms" }
                        batch.failure?.let { observations += "$query\tfailure=${it.javaClass.simpleName}" }
                    }
                }
                observations += "$query\texpected=$expected\tactual=$ids"
                report("wenku8-search-live.txt", observations.joinToString("\n"))
                assertTrue("Missing targets for $query: $ids", ids.containsAll(expected))
            }
        } finally {
            report("wenku8-search-live.txt", observations.joinToString("\n"))
            api.close()
        }
    }

    @Test fun liveSourceSearchWorksWhenSupplementalServicesAreUnavailable() = runBlocking {
        live()
        val app = instrumentation.targetContext.applicationContext as NextVolApplication
        val api = Wenku8Api(app.wenku8SearchSupport) { id -> app.sourceNetworkSettings.forSource(id).snapshot() }
        val provider = api.searchProvider as indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8SearchProvider
        val observations = mutableListOf<String>()
        try {
            for ((query, expected) in listOf("義妹生活" to setOf("2883"), "1973" to setOf("1973", "4350"),
                "#1973" to setOf("1973"), "春物" to emptySet(), "实教" to emptySet())) {
                val start = android.os.SystemClock.elapsedRealtime()
                val batches = withTimeout(35_000) {
                    provider.searchPageUpdates(provider.searchTypes.first(), query, 1, "device-$query").onEach { batch ->
                        observations += "$query\tcomplete=${batch.complete}\tids=${batch.books.map { it.bookId }}" +
                            "\tfailure=${batch.failure?.javaClass?.simpleName}\t${android.os.SystemClock.elapsedRealtime() - start}ms"
                    }.toList()
                }
                report("wenku8-search-source.txt", observations.joinToString("\n"))
                assertTrue(batches.last().complete)
                assertNull("Wenku8 search failed for $query", batches.last().failure)
                assertTrue("Missing catalogue candidates for $query", batches.last().books.map { it.bookId }.containsAll(expected))
            }
        } finally {
            report("wenku8-search-source.txt", observations.joinToString("\n"))
            api.close()
        }
    }
}
