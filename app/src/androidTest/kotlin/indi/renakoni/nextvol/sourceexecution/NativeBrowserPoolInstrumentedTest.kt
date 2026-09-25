package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.view.Choreographer
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class NativeBrowserPoolInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun html(body: String) = "<html><head><link rel='icon' href='data:,'></head><body>$body</body></html>"
    private suspend fun read(session: SourceSession, server: MockWebServer, path: String): BrokerResponse {
        val result = session.execute(BrokerRequest(path, server.url(path).toString(), timeoutMillis = 30000))
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }
    private suspend fun fixture(block: suspend (SourceSession, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val root = File(context.cacheDir, "native-pool-${System.nanoTime()}")
            try { SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val url = server.url("/").toString()
                val session = broker.open(SourceScope("native-pool", root.name, "test"), listOf(NetworkGrant(url, true)))
                session.configureSource(url, true, browserRead = true)
                try { block(session, server) } finally { session.clearAccount() }
            } } finally { root.deleteRecursively() }
        }
    }

    @Test fun independentPagesReachTheServerBeforeEitherResponseIsReleased() = runBlocking {
        fixture { session, server ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    entered.countDown()
                    try { check(release.await(25, TimeUnit.SECONDS)) }
                    finally { active.decrementAndGet() }
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            val first = async { read(session, server, "/one") }
            val second = async { read(session, server, "/two") }
            try { withContext(Dispatchers.IO) { assertTrue(entered.await(20, TimeUnit.SECONDS)) } }
            finally { release.countDown() }
            assertTrue(withTimeout(10000) { first.await() }.text().contains("/one"))
            assertTrue(withTimeout(10000) { second.await() }.text().contains("/two"))
            assertEquals(2, maximum.get())
        }
    }

    @Test fun cancellingOnePageKeepsTheOtherPageAndItsBrowserAlive() = runBlocking {
        fixture { session, server ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown(); check(release.await(25, TimeUnit.SECONDS))
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            val first = async { read(session, server, "/cancelled") }
            val second = async { read(session, server, "/survivor") }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(20, TimeUnit.SECONDS)) }
                withTimeout(7000) { first.cancelAndJoin() }
                assertFalse(second.isCompleted)
            } finally { release.countDown() }
            assertTrue(withTimeout(10000) { second.await() }.text().contains("/survivor"))
            assertTrue(first.isCancelled)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun overlappingPagesCannotReplayOldCookieSeedsAndExplicitHostUpdatesStillApply() = runBlocking {
        fixture { session, server ->
            val changed = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/change" -> MockResponse().setHeader("Set-Cookie", "shared=website; Path=/; HttpOnly")
                        .setHeader("Content-Type", "text/html").setBody(html("<script>fetch('/signal')</script><img src='/hold'>"))
                    "/signal" -> { changed.countDown(); MockResponse().setBody("ok") }
                    "/hold" -> { check(release.await(25, TimeUnit.SECONDS)); MockResponse().setResponseCode(404) }
                    else -> MockResponse().setBody(html(request.getHeader("Cookie").orEmpty()))
                }
            }
            val url = server.url("/").toString()
            session.setCookie(url, "shared=seed")
            val changing = async { read(session, server, "/change") }
            try {
                withContext(Dispatchers.IO) { assertTrue(changed.await(20, TimeUnit.SECONDS)) }
                assertFalse(changing.isCompleted)
                assertTrue(withTimeout(10000) { read(session, server, "/check") }.text().contains("shared=website"))
                session.setCookie(url, "shared=seed")
                assertTrue(withTimeout(10000) { read(session, server, "/reset") }.text().contains("shared=seed"))
            } finally { release.countDown() }
            withTimeout(10000) { changing.await() }
            assertTrue(session.cookie(url).contains("shared=seed"))
        }
    }

    /** Separate from latency samples: memory capture itself adds work. Frame gaps measure host
     * main-loop scheduling, not the discovery screen's rendered-frame jank rate. */
    @Test fun measureBoundedPageResources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativePoolResources") == "true")
        val count = requireNotNull(args.getString("nativePoolPages")).toInt().also { require(it in listOf(1, 2, 4)) }
        repeat(3) { iteration -> fixture { session, server ->
            val loaded = CountDownLatch(count)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/hold/")) {
                        loaded.countDown(); check(release.await(25, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(404)
                    }
                    val rows = (1..30).joinToString("") { "<li><a href='/book/$it'>Book $it</a></li>" }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(html("<ul>$rows</ul><img src='/hold${request.path}'>"))
                }
            }
            val gaps = CopyOnWriteArrayList<Long>()
            var previous = 0L
            val frames = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (previous != 0L) gaps += time - previous
                    previous = time
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(frames) }
            val gcBefore = Debug.getRuntimeStats()["art.gc.gc-count"]?.toLongOrNull() ?: 0
            val reads = (1..4).map { async { read(session, server, "/page$it") } }
            try {
                try {
                    withContext(Dispatchers.IO) { assertTrue(loaded.await(20, TimeUnit.SECONDS)) }
                    val memory = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                        "dumpsys meminfo -s ${context.packageName}")).bufferedReader().use { it.readText() }
                    File(context.filesDir, "native-pool-memory-$iteration.txt").writeText(memory)
                } finally { release.countDown() }
                withTimeout(15000) { reads.awaitAll() }
            }
            finally { instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(frames) } }
            val report = buildJsonObject {
                put("label", args.getString("nativePoolLabel") ?: "local")
                put("pages", count); put("iteration", iteration)
                put("mainGcCount", (Debug.getRuntimeStats()["art.gc.gc-count"]?.toLongOrNull() ?: 0) - gcBefore)
                put("mainFrameIntervals", gaps.size)
                put("mainGapsOver32ms", gaps.count { it > 32_000_000 })
                put("mainMaxGapMs", (gaps.maxOrNull() ?: 0) / 1_000_000.0)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("nativePoolResources", report.toString()) })
        } }
    }
}
