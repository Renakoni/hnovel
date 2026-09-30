package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
    @Test fun savedLoginCookieDoesNotBlockBrowserRotationOrOverrideItsUpdatedSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-login-handoff-${System.nanoTime()}")
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/protected") return MockResponse().setHeader("Content-Type", "text/plain")
                        .setResponseCode(if (request.getHeader("Cookie") == "auth=fresh") 200 else 401).setBody("fixture")
                    return MockResponse().setHeader("Content-Type", "text/html")
                        .addHeader("Set-Cookie", "auth=fresh; Path=/; HttpOnly; SameSite=Lax")
                        .setBody("<html><head><link rel='icon' href='data:,'></head><body>done</body></html>")
                }
            }
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val base = server.url("/").toString()
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val session = broker.open(SourceScope("native-login-handoff", root.name, "test"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, browserRead = true)
                val login = StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS)
                session.write(login.copy(value = """{"Cookie":"auth=old","Authorization":"Bearer keep"}"""))
                session.setCookie(base, "auth=old")
                suspend fun read(): Int {
                    val result = session.execute(BrokerRequest("read", server.url("/protected").toString(), kind = ResourceKind.Api))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    return (result as BrokerResult.Success).response.status
                }
                try {
                    assertEquals(401, read())
                    val browser = session.execute(BrokerRequest("rotate", server.url("/rotate").toString(),
                        timeoutMillis = 30000, browser = BrowserOptions(script = "document.body.textContent")))
                    assertTrue(browser.toString(), browser is BrokerResult.Success)
                    assertEquals("auth=fresh", session.cookie(base))
                    assertEquals(200, read())
                    assertEquals("""{"Authorization":"Bearer keep"}""", (session.read(login) as StorageResult.Value).value)
                } finally { session.clearAccount() }
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun parentDomainCookiesReachSubdomainsAndSurviveBrowserProcessReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-domain-cookie-${System.nanoTime()}")
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                    if (request.path == "/seed") response
                        .addHeader("Set-Cookie", "account=fixture; Domain=reader.localhost; Path=/; Max-Age=600; HttpOnly")
                        .addHeader("Set-Cookie", "host=fixture; Path=/; Max-Age=600")
                    return response.setBody("""<html><head><link rel='icon' href='data:,'></head><body><script>
                        window.snapshot=JSON.stringify({sent:${JsonPrimitive(request.getHeader("Cookie").orEmpty())},visible:document.cookie});
                        </script></body></html>""")
                }
            }
            server.start(loopback, 0)
            // Chromium resolves *.localhost to loopback without relying on an external DNS service.
            val url = server.url("/").newBuilder().host("login.reader.localhost").build().toString()
            val sibling = server.url("/").newBuilder().host("www.reader.localhost").build().toString()
            SourceBroker(root.toPath(), dns = okhttp3.Dns { listOf(loopback) }, browser = AndroidSourceBrowser(context)).use { broker ->
                val grants = listOf(NetworkGrant(url, true), NetworkGrant(sibling, true))
                val account = broker.open(SourceScope("native-domain-cookie", root.name, "test"), grants)
                val other = broker.open(SourceScope("native-domain-cookie", "other-${root.name}", "test"), grants)
                listOf(account, other).forEach { it.configureSource(url, true, browserRead = true) }
                suspend fun read(session: SourceSession, target: String): JsonObject {
                    val result = session.execute(BrokerRequest("read", target, timeoutMillis = 30000,
                        browser = BrowserOptions(script = "window.snapshot || null")))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    return Json.parseToJsonElement((result as BrokerResult.Success).response.text()).jsonObject
                }
                try {
                    assertTrue(account.execute(BrokerRequest("seed", url + "seed", kind = ResourceKind.Api)) is BrokerResult.Success)
                    assertEquals(2, account.nativeBrowserCookieSeed(url).cookies.size)
                    val first = read(account, url)
                    assertEquals(setOf("account=fixture", "host=fixture"), first.getValue("sent").jsonPrimitive.content.split("; ").toSet())
                    assertEquals("host=fixture", first.getValue("visible").jsonPrimitive.content)
                    assertEquals("", read(other, url).getValue("sent").jsonPrimitive.content)
                    // Switching profiles replaces the dedicated process; the original account must retain its scope.
                    val restored = read(account, sibling)
                    assertEquals("account=fixture", restored.getValue("sent").jsonPrimitive.content)
                    assertEquals("", restored.getValue("visible").jsonPrimitive.content)
                } finally { account.clearAccount(); other.clearAccount() }
            }
        } } finally { root.deleteRecursively() }
    }

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
