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
            val lateCookie = CountDownLatch(1)
            val resetEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/change" -> MockResponse().setHeader("Set-Cookie", "shared=website; Path=/; HttpOnly")
                        .setHeader("Content-Type", "text/html").setBody(html("<script>fetch('/signal')</script><img src='/hold' onload=\"fetch('/late-signal')\">"))
                    "/signal" -> { changed.countDown(); MockResponse().setBody("ok") }
                    "/late-signal" -> { lateCookie.countDown(); MockResponse().setBody("ok") }
                    "/hold" -> {
                        check(release.await(25, TimeUnit.SECONDS))
                        MockResponse().setHeader("Set-Cookie", "shared=stale; Path=/; HttpOnly")
                            .setHeader("Content-Type", "image/svg+xml")
                            .setBody("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>")
                    }
                    "/reset" -> {
                        resetEntered.countDown()
                        check(lateCookie.await(20, TimeUnit.SECONDS))
                        MockResponse().setBody(html(request.getHeader("Cookie").orEmpty()))
                    }
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
                val reset = async { read(session, server, "/reset") }
                // A wrongly shared reset reaches the server before the old response changes cookies.
                // Correct admission keeps it queued until the old batch below is released.
                withContext(Dispatchers.IO) { resetEntered.await(2, TimeUnit.SECONDS) }
                release.countDown()
                assertTrue(withTimeout(10000) { reset.await() }.text().contains("shared=seed"))
            } finally { release.countDown() }
            withTimeout(10000) { changing.await() }
            assertTrue(session.cookie(url).contains("shared=seed"))
        }
    }

    @Test fun cancellingTheLastSharedPageAlsoStopsItsServiceWorker() = runBlocking {
        fixture { session, server ->
            val release = CountDownLatch(1)
            val pulses = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/start" -> MockResponse().setHeader("Content-Type", "text/html").setBody(html(
                        "<script>navigator.serviceWorker.register('/worker.js')</script><img src='/hold'>"))
                    "/worker.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                        .setBody("self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));setInterval(()=>fetch('/pulse'),150)")
                    "/pulse" -> { pulses.incrementAndGet(); MockResponse().setBody("ok") }
                    "/hold" -> { check(release.await(25, TimeUnit.SECONDS)); MockResponse().setResponseCode(404) }
                    else -> MockResponse().setBody(html("new page"))
                }
            }
            val pending = async { read(session, server, "/start") }
            try {
                withTimeout(15000) { while (pulses.get() < 2) delay(50) }
                withTimeout(7000) { pending.cancelAndJoin() }
                delay(500)
                val stopped = pulses.get()
                delay(700)
                assertEquals(stopped, pulses.get())
                assertTrue(read(session, server, "/next").text().contains("new page"))
            } finally { release.countDown() }
        }
    }

    @Test fun sharedPagesPreserveImageLoadGeneratedContentAndLegitimateEmptyPages() = runBlocking {
        fixture { session, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/dynamic" -> MockResponse().setHeader("Content-Type", "text/html").setBody(html(
                        "<ul></ul><img src='/image.svg' onload=\"var li=document.createElement('li');li.textContent='late book';document.querySelector('ul').appendChild(li)\">"))
                    "/image.svg" -> MockResponse().setHeader("Content-Type", "image/svg+xml")
                        .setBody("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><rect width='1' height='1'/></svg>")
                    else -> MockResponse().setHeader("Content-Type", "text/html").setBody(html("<ul></ul>"))
                }
            }
            val dynamic = async { read(session, server, "/dynamic") }
            val empty = async { read(session, server, "/empty") }
            assertTrue(dynamic.await().text().contains("<li>late book</li>"))
            assertTrue(empty.await().text().contains("<ul></ul>"))
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
                    val packages = listOfNotNull(context.packageName,
                        androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.packageName).distinct()
                    val memory = packages.joinToString("\n") { name ->
                        "PACKAGE_SCOPE $name\n" + ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                            "dumpsys meminfo -s --package $name")).bufferedReader().use { it.readText() }
                    }
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
