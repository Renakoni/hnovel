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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeBrowserAdmissionCookieInstrumentedTest {
    @Test fun cookieChangedDuringAdmissionWaitsForTheOldSiblingBeforeStartingANewBatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-admission-sibling-${System.nanoTime()}")
        val release = CountDownLatch(1)
        try { MockWebServer().use { server ->
            val oldEntered = CountDownLatch(1)
            val queuedEntered = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = MockResponse().setHeader("Content-Type", "text/html")
                    if (request.path == "/old") {
                        oldEntered.countDown()
                        check(release.await(25, TimeUnit.SECONDS))
                        response.setHeader("Set-Cookie", "account=late; Path=/; HttpOnly")
                    }
                    if (request.path == "/queued") queuedEntered.countDown()
                    return response.setBody("<html><head><link rel='icon' href='data:,'></head><body>${request.getHeader("Cookie").orEmpty()}</body></html>")
                }
            }
            server.start()
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val url = server.url("/").toString()
                val session = broker.open(SourceScope("native-admission", root.name, "test"), listOf(NetworkGrant(url, true)))
                session.configureSource(url, true, browserRead = true)
                try {
                    session.setCookie(url, "account=old")
                    assertTrue(session.execute(BrokerRequest("warm", url)) is BrokerResult.Success)
                    val old = async { session.execute(BrokerRequest("old", server.url("/old").toString())) }
                    withContext(Dispatchers.IO) { assertTrue(oldEntered.await(10, TimeUnit.SECONDS)) }
                    session.configureSource(url, true, browserRead = true, concurrentRate = "1000")
                    session.awaitBrowserAdmission()
                    val changed = CompletableDeferred<Unit>()
                    val checkpoints = AtomicInteger()
                    val queued = async { session.execute(BrokerRequest("queued", server.url("/queued").toString()), RequestCommitGuard { action ->
                        action()
                        if (checkpoints.incrementAndGet() == 2) {
                            session.setCookie(url, "account=new")
                            changed.complete(Unit)
                        }
                    }) }
                    withTimeout(5000) { changed.await() }
                    withContext(Dispatchers.IO) { assertFalse(queuedEntered.await(500, TimeUnit.MILLISECONDS)) }
                    assertFalse(queued.isCompleted)
                    release.countDown()
                    assertTrue(withTimeout(10000) { old.await() } is BrokerResult.Success)
                    val result = withTimeout(10000) { queued.await() }
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    assertTrue((result as BrokerResult.Success).response.text(), result.response.text().contains("account=new"))
                    assertEquals("account=new", session.cookie(url))
                } finally { release.countDown(); session.clearAccount() }
            }
        } } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test fun hostCookieChangedDuringAdmissionIsUsedBeforeTheFirstNavigation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-admission-cookie-${System.nanoTime()}")
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><head><link rel='icon' href='data:,'></head><body>${request.getHeader("Cookie").orEmpty()}</body></html>")
            }
            server.start()
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val url = server.url("/").toString()
                val session = broker.open(SourceScope("native-admission", root.name, "test"), listOf(NetworkGrant(url, true)))
                session.configureSource(url, true, browserRead = true)
                try {
                    session.setCookie(url, "account=old")
                    assertTrue(session.execute(BrokerRequest("warm", url)) is BrokerResult.Success)
                    session.configureSource(url, true, browserRead = true, concurrentRate = "1000")
                    session.awaitBrowserAdmission()
                    val changed = AtomicBoolean()
                    val checkpoints = AtomicInteger()
                    val started = System.nanoTime()
                    val result = session.execute(BrokerRequest("queued", server.url("/queued").toString()), RequestCommitGuard { action ->
                        action()
                        // This guard is reached again after the source's admission wait. Change
                        // the host credential at that dispatch boundary, before service.start.
                        if (checkpoints.incrementAndGet() == 2) {
                            assertTrue("The second checkpoint follows admission", System.nanoTime() - started >= 800_000_000)
                            changed.set(true)
                            session.setCookie(url, "account=new")
                        }
                    })
                    assertTrue("The credential must change before navigation", changed.get())
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    assertTrue((result as BrokerResult.Success).response.text(), result.response.text().contains("account=new"))
                    assertEquals("account=new", session.cookie(url))
                } finally { session.clearAccount() }
            }
        } } finally { root.deleteRecursively() }
    }
}
