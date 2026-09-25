package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
}
