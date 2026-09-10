package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Chromium assertions on API 24 and 35. No public site or account participates. */
@RunWith(AndroidJUnit4::class)
class SourceBrowserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rendersThroughBrokerAndKeepsSameDomainAccountsAndBrowserStorageSeparate(): Unit = runBlocking {
        val seen = ConcurrentLinkedQueue<String>()
        MockWebServer().use { denied -> MockWebServer().use { server ->
            denied.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/redirect") return MockResponse().setResponseCode(302).addHeader("Location", "/page")
                    if (request.path == "/api") {
                        seen.add(request.getHeader("Cookie").orEmpty())
                        return MockResponse().setBody("ok").addHeader("Set-Cookie", "hidden=server; HttpOnly; Path=/")
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><head><title>initial</title></head><body><main></main><script>
                        var prior=localStorage.getItem('owner')||'empty';
                        localStorage.setItem('owner','persisted');document.cookie='visible=page; Path=/';
                        var blocked='${denied.url("/forbidden")}';
                        fetch(blocked).catch(function(){});
                        try{new WebSocket(blocked.replace('http','ws'))}catch(e){}
                        var frame=document.createElement('iframe');frame.src=blocked;document.body.appendChild(frame);
                        fetch('/api',{method:'POST',body:'field=value',headers:{'Content-Type':'application/x-www-form-urlencoded'}})
                          .then(function(r){return r.text()}).then(function(body){
                            document.title=prior+':'+body+':'+document.cookie;window.finished=true;});
                        </script></body></html>
                    """.trimIndent())
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-test-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val grants = listOf(NetworkGrant(server.url("/").toString(), true))
                fun session(id: String) = broker.open(SourceScope("browser-fixture", id, "legado", 1), grants).apply {
                    configureSource(server.url("/").toString(), true)
                }
                val a = session("A"); val b = session("B")
                a.setCookie(server.url("/").toString(), "account=alice")
                b.setCookie(server.url("/").toString(), "account=bob")
                suspend fun render(session: SourceSession, path: String = "/page"): String {
                    val result = session.execute(BrokerRequest("render", server.url(path).toString(), timeoutMillis = 60000,
                        browser = BrowserOptions(script = "window.finished ? document.title : null")))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    return (result as BrokerResult.Success).response.text()
                }
                assertTrue(render(a).startsWith("empty:ok:"))
                assertTrue(render(b).startsWith("empty:ok:"))
                assertTrue(render(a, "/redirect").startsWith("persisted:ok:"))
                a.clearAccount()
                val next = broker.open(SourceScope("browser-fixture", "A", "legado", 2), grants)
                assertEquals("", next.cookie(server.url("/").toString()))
                assertTrue(render(next).startsWith("persisted:ok:"))
                assertEquals(4, seen.size)
                assertTrue(seen.elementAt(0).contains("account=alice"))
                assertTrue(seen.elementAt(1).contains("account=bob"))
                assertFalse(next.browserCookie(server.url("/").toString()).contains("hidden"))
                assertTrue(next.cookie(server.url("/").toString()).contains("hidden=server"))
                assertEquals(0, denied.requestCount)
                val name = if (android.os.Build.VERSION.SDK_INT >= 28) "app_webview_source_browser" else "app_webview"
                assertFalse(File(context.applicationInfo.dataDir, name).exists())
            }
            root.deleteRecursively()
        } }
    }

    @Test fun cancelledPageCannotRestoreCookiesAndTheNextOwnerCanRender(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>late</p>")
                .addHeader("Set-Cookie", "late=secret; Max-Age=3600; Path=/").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<title>next</title>"))
            server.start()
            val root = File(context.cacheDir, "browser-cancel-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val grant = listOf(NetworkGrant(server.url("/").toString(), true))
                val old = broker.open(SourceScope("cancel", "A", "legado", 1), grant)
                val pending = async { runCatching { old.execute(BrokerRequest("cancel", server.url("/").toString(),
                    timeoutMillis = 60000, browser = BrowserOptions())) } }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(15, java.util.concurrent.TimeUnit.SECONDS)) }
                old.clearAccount()
                withTimeout(10000) { pending.join() }
                val next = broker.open(SourceScope("cancel", "A", "legado", 2), grant)
                val result = next.execute(BrokerRequest("next", server.url("/").toString(), timeoutMillis = 60000,
                    browser = BrowserOptions("document.title"))) as BrokerResult.Success
                assertEquals("next", result.response.text())
                assertEquals("", next.cookie(server.url("/").toString()))
            }
            root.deleteRecursively()
        }
    }

    @Test fun foregroundLoginPreservesPostResponseAndCommitsOnlyItsOwnCookies(): Unit = runBlocking {
        context.startActivity(android.content.Intent(context, indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.BrowserTestHostActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(10000) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Browser test host").orEmpty().isEmpty()) delay(100)
        }
        MockWebServer().use { server ->
            val posted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        assertEquals("user=fixture", request.body.readUtf8())
                        posted.complete(Unit)
                        return MockResponse().setHeader("Content-Type", "text/html").addHeader("Set-Cookie", "auth=accepted; Path=/; HttpOnly")
                            .setBody("<html><title>POST accepted</title><body>Signed in</body></html>")
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><body><form method="post" action="/login"><input name="user" value="fixture"></form>
                        <script>setTimeout(function(){document.forms[0].submit()},100);</script></body></html>
                    """.trimIndent())
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-login-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val session = broker.open(SourceScope("login", "A", "legado", 1), listOf(NetworkGrant(server.url("/").toString(), true)))
                val login = async { session.execute(BrokerRequest("login", server.url("/login").toString(), timeoutMillis = 60000,
                    headers = mapOf("User-Agent" to "source-login-agent", "Authorization" to "Bearer source-login", "Cookie" to "source=login"),
                    browser = BrowserOptions(script = "document.title === 'POST accepted' ? document.title : null", interactive = true))) }
                val formPosted = withTimeoutOrNull(20000) { posted.await(); true } == true
                assertTrue("Form POST did not reach broker; requests=${server.requestCount}, browserCompleted=${login.isCompleted}", formPosted)
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val label = context.getString(indi.dmzz_yyhyy.lightnovelreader.R.string.source_browser_done)
                val clicked = withTimeoutOrNull(20000) {
                    while (true) {
                        val rootNode = automation.rootInActiveWindow
                        val button = rootNode?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
                        if (button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) break
                        delay(100)
                    }
                    true
                }
                assertTrue("Login window did not expose rendered POST response and finish action", clicked == true)
                val result = login.await()
                assertTrue(result.toString(), result is BrokerResult.Success)
                assertEquals("POST accepted", (result as BrokerResult.Success).response.text())
                assertEquals("auth=accepted", session.cookie(server.url("/").toString()))
                val navigation = List(server.requestCount) { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!! }
                    .filter { it.path == "/login" }
                assertEquals(listOf("GET", "POST"), navigation.map { it.method })
                assertEquals("source-login-agent", navigation.first().getHeader("User-Agent"))
                assertEquals("Bearer source-login", navigation.first().getHeader("Authorization"))
                assertEquals("source=login", navigation.first().getHeader("Cookie"))
            }
            root.deleteRecursively()
        }
    }
}
