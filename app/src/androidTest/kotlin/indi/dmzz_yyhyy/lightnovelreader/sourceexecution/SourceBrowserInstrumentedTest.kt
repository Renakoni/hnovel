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

    @Test fun rendersThroughBrokerAndKeepsSameDomainAccountsAndBrowserStorageSeparate() = runBlocking {
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
                assertEquals(3, seen.size)
                assertTrue(seen.elementAt(0).contains("account=alice"))
                assertTrue(seen.elementAt(1).contains("account=bob"))
                assertFalse(a.browserCookie(server.url("/").toString()).contains("hidden"))
                assertTrue(a.cookie(server.url("/").toString()).contains("hidden=server"))
                assertEquals(0, denied.requestCount)
            }
            root.deleteRecursively()
        } }
    }
}
