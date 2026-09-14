package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.AndroidSourceBrowser
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeBrowserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<SourceSession>()

    private suspend fun fixture(block: suspend (SourceBroker, MockWebServer) -> Unit) {
        val root = File(context.cacheDir, "native-test-${UUID.randomUUID()}")
        MockWebServer().use { server ->
            server.start()
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                try { block(broker, server) }
                finally { scopes.forEach { it.clearAccount() }; scopes.clear() }
            }
        }
        root.deleteRecursively()
    }
    private fun session(broker: SourceBroker, server: MockWebServer, id: String = UUID.randomUUID().toString(), generation: Long = 0): SourceSession =
        broker.open(SourceScope("native-tests", id, "legado", generation), listOf(NetworkGrant(server.url("/").toString(), true)))
            .apply { configureSource(server.url("/").toString(), true, browserRead = true); scopes += this }

    private suspend fun render(session: SourceSession, url: String, script: String = "document.title", interactive: Boolean = false): BrokerResponse {
        val result = session.execute(BrokerRequest("render", url, timeoutMillis = 60000,
            browser = BrowserOptions(script = script, interactive = interactive)))
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }

    @Test fun nativeIframeFetchCookiesAndFinalUrlArePreserved(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/start" -> MockResponse().setResponseCode(302).setHeader("Location", "/page")
                "/frame" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<p>frame-ready</p>")
                "/echo" -> MockResponse().setBody("${request.method}|${request.body.readUtf8()}|${request.getHeader("Cookie")}")
                "/page" -> MockResponse().setHeader("Content-Type", "text/html")
                    .addHeader("Set-Cookie", "visible=fixture; Path=/")
                    .addHeader("Set-Cookie", "hidden=fixture; HttpOnly; Path=/")
                    .setBody("""
                        <html><head><link rel="icon" href="data:,"></head><body><iframe src="/frame"></iframe>
                        <iframe srcdoc="<p>srcdoc-ready</p>"></iframe><iframe src="about:blank"></iframe>
                        <script>window.addEventListener('load',async function(){
                            var echo=await (await fetch('/echo',{method:'POST',body:'field=value'})).text();
                            window.answer=JSON.stringify({echo:echo,frame:frames[0].document.body.textContent,
                                cookies:document.cookie,bridge:typeof SourceBrowser,
                                srcdoc:frames[1].document.body.textContent,blank:frames[2].location.href,
                                nativeFetch:/\[native code\]/.test(Function.prototype.toString.call(fetch)),
                                webdriver:navigator.webdriver,frameWebdriver:frames[0].navigator.webdriver});
                        });</script></body></html>
                    """.trimIndent())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val response = render(session(broker, server), server.url("/start").toString(), "window.answer || null")
        assertEquals(ResponseKind.BrowserDocument, response.kind)
        assertEquals(0, response.status); assertEquals("", response.protocol); assertTrue(response.headers.isEmpty())
        assertEquals(server.url("/page").toString(), response.finalUrl)
        val result = Json.parseToJsonElement(response.text()).jsonObject
        assertEquals("frame-ready", result.getValue("frame").jsonPrimitive.content)
        assertEquals("srcdoc-ready", result.getValue("srcdoc").jsonPrimitive.content)
        assertEquals("about:blank", result.getValue("blank").jsonPrimitive.content)
        assertEquals("visible=fixture", result.getValue("cookies").jsonPrimitive.content)
        assertEquals("undefined", result.getValue("bridge").jsonPrimitive.content)
        assertTrue(result.getValue("nativeFetch").jsonPrimitive.boolean)
        assertFalse(result.getValue("webdriver").jsonPrimitive.boolean)
        assertEquals(result["webdriver"], result["frameWebdriver"])
        val echo = result.getValue("echo").jsonPrimitive.content
        assertTrue(echo, echo.startsWith("POST|field=value|"))
        assertTrue(echo, echo.contains("hidden=fixture"))
    } }

    @Test fun tasksKeepSessionCookiesAndAccountSwitchesKeepStorageIsolated(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val set = request.path == "/set"
                return MockResponse().setHeader("Content-Type", "text/html").apply {
                    if (set) {
                        addHeader("Set-Cookie", "persistent=fixture; Max-Age=3600; Path=/")
                        addHeader("Set-Cookie", "session=fixture; Path=/; HttpOnly")
                    }
                    setBody("""<html><head><link rel="icon" href="data:,"></head><body><script>
                        ${if (set) "localStorage.setItem('owner','alice');" else ""}
                        document.title=(localStorage.getItem('owner')||'empty')+'|'+${JsonPrimitive(request.getHeader("Cookie").orEmpty())};
                        </script></body></html>""")
                }
            }
        }
        val a = session(broker, server)
        render(a, server.url("/set").toString())
        val same = render(a, server.url("/read").toString()).text()
        assertTrue(same, same.startsWith("alice|")); assertTrue(same, same.contains("session=fixture"))
        assertEquals("empty|", render(session(broker, server), server.url("/read").toString()).text())
        val restored = render(a, server.url("/read").toString()).text()
        assertTrue(restored, restored.startsWith("alice|")); assertTrue(restored, restored.contains("persistent=fixture"))
        a.clearAccount()
        val next = session(broker, server, a.scope.sourceId, 1)
        assertEquals("empty|", render(next, server.url("/read").toString()).text())
    } }

    @Test fun foregroundNativeFormReturnsItsPostDocument(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setHeader("Content-Type", "text/html").setBody(
                    if (request.method == "POST") {
                        assertEquals("user=fixture", request.body.readUtf8())
                        "<html><title>accepted</title><body>fixture</body></html>"
                    } else """<html><body><form method="post" action="/signed-in"><input name="user" value="fixture"></form>
                        <script>document.forms[0].submit()</script></body></html>""")
            }
            val response = render(session(broker, server), server.url("/login").toString(),
                "document.title==='accepted' ? document.title : null", interactive = true)
            assertEquals("accepted", response.text())
            assertEquals(server.url("/signed-in").toString(), response.finalUrl)
        } }
    }

    @Test fun cancellationRetiresTheOldAccountBeforeAnotherRequest(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody(
                if (request.path == "/wait") "<html><script>setTimeout(function(){document.cookie='late=fixture';document.title='late'},5000)</script></html>"
                else "<html><script>document.title=document.cookie || 'empty'</script></html>")
        }
        val old = session(broker, server)
        val pending = async { runCatching { render(old, server.url("/wait").toString(), "document.title==='late' ? document.title : null") } }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(20, TimeUnit.SECONDS)) }
        withContext(Dispatchers.IO) { old.clearAccount() }
        withTimeout(10000) { pending.await() }
        val next = session(broker, server, old.scope.sourceId, 1)
        assertEquals("empty", render(next, server.url("/read").toString()).text())
    } }

    @Test fun challengeKeepsPendingTargetAndForegroundSessionCanResume(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/protected" -> MockResponse().setResponseCode(302).setHeader("Location", "/antibot")
                    "/antibot" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><title>Just a moment</title><script>
                        setTimeout(function(){location.replace('/accepted')},2500)
                        </script></html>
                    """.trimIndent())
                    else -> MockResponse().setHeader("Content-Type", "text/html")
                        .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                        .setBody("<html><title>accepted</title></html>")
                }
            }
            val account = session(broker, server)
            val target = server.url("/protected").toString()
            val blocked = account.execute(BrokerRequest("read", target, timeoutMillis = 60000))
            assertEquals(FailureCode.BrowserRequired, (blocked as BrokerResult.Failure).code)
            assertEquals(target, (account.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as StorageResult.Value).value)
            val resumed = render(account, target, "document.title==='accepted' ? document.title : null", interactive = true)
            assertEquals("accepted", resumed.text())
            assertEquals(server.url("/accepted").toString(), resumed.finalUrl)
        } }
    }

    @Test fun cacheIsReusableWithinAccountButNeverSharedAcrossAccounts(): Unit = runBlocking { fixture { broker, server ->
        val hits = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/cached") {
                MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(hits.incrementAndGet().toString())
            } else MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").setBody("""
                <html><script>fetch('/cached').then(r=>r.text()).then(t=>{window.answer=t})</script></html>
            """.trimIndent())
        }
        val a = session(broker, server)
        val url = server.url("/page").toString()
        assertEquals("1", render(a, url, "window.answer || null").text())
        assertEquals("1", render(a, url, "window.answer || null").text())
        assertEquals("2", render(session(broker, server), url, "window.answer || null").text())
        // Relocated profiles may retain A's cache; the API 28 fallback discards it.
        assertTrue(render(a, url, "window.answer || null").text() in listOf("1", "3"))
    } }

    @Test fun ordinaryHttpStillReturnsRawStatusAndDoesNotExecuteScripts(): Unit = runBlocking { fixture { broker, server ->
        server.enqueue(MockResponse().setResponseCode(202).setHeader("X-Fixture", "raw").setBody("<script>fetch('/unexpected')</script>"))
        val session = broker.open(SourceScope("native-tests", UUID.randomUUID().toString(), "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
        val response = (session.execute(BrokerRequest("http", server.url("/").toString())) as BrokerResult.Success).response
        assertEquals(ResponseKind.Http, response.kind); assertEquals(202, response.status)
        assertEquals(listOf("raw"), response.headers.entries.firstOrNull { it.key.equals("X-Fixture", true) }?.value)
        assertEquals(1, server.requestCount)
    } }

    /** Explicit local URL allows the same fixture to be run in Chrome and reference MD3. */
    @Test fun recordLocalEnvironment(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("browserProbeUrl")
        assumeTrue(url != null)
        require(url!!.startsWith("http://127.0.0.1:18766/probe"))
        val root = File(context.cacheDir, "native-probe-${UUID.randomUUID()}")
        SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
            val session = broker.open(SourceScope("browser-probe", UUID.randomUUID().toString(), "legado"),
                listOf(NetworkGrant("http://127.0.0.1:18766", true)))
            session.configureSource("http://127.0.0.1:18766", true, args.getString("nativeProbe") == "true")
            try {
                val result = render(session, url, "window.probeDone ? JSON.stringify(window.probeResult) : null")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("browserProbe", result.text()) })
            } finally { session.clearAccount() }
        }
        root.deleteRecursively()
    }
}
