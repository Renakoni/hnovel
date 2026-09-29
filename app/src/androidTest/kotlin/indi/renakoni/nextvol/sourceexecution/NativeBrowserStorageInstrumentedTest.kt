package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.nativeBrowserProfile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeBrowserStorageInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun logoutPreservesDeclaredPreferencesButClearsUnknownDataAndCookies(): Unit = runBlocking { fixture {
        val old = open()
        old.write(StorageRequest(StorageArea.Config, "source/value", "keep"))
        old.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, "old-credentials"))
        old.setCookie(url(), "broker=old")
        seed(old)
        val saved = read(old)
        assertEquals("old", saved["payload"]!!.jsonPrimitive.content)
        assertEquals("old", saved["indexed"]!!.jsonPrimitive.content)
        assertEquals(setOf("broker=old", "account=old"), saved["cookie"]!!.jsonPrimitive.content.split(';').map(String::trim).toSet())
        val count = server.requestCount
        old.clearAccount()
        assertEquals("Account cleanup must not visit the website", count, server.requestCount)
        val next = open(1)
        assertEquals(StorageResult.Value("keep"), next.read(StorageRequest(StorageArea.Config, "source/value")))
        assertEquals(StorageResult.Value(null), next.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)))
        assertEquals("", next.cookie(url()))
        val restored = read(next)
        assertClean(restored, "dark")
        assertEquals(saved["ua"], restored["ua"])
        assertEquals(saved["webdriver"], restored["webdriver"])
        assertEquals(JsonPrimitive(false), restored["webdriver"])
        assertEquals(JsonPrimitive("undefined"), restored["bridge"])
    } }

    @Test fun sameOriginSourcesKeepTheirOwnPreferencesAndAccounts(): Unit = runBlocking { fixture {
        val a = open()
        val b = open(source = UUID.randomUUID().toString())
        seed(a)
        seed(b, theme = "light", account = "second")
        a.clearAccount()
        assertClean(read(open(1)), "dark")
        val other = read(b)
        assertEquals("light", other["appearance"]!!.jsonPrimitive.content)
        assertEquals("second", other["payload"]!!.jsonPrimitive.content)
        assertEquals("account=second", other["cookie"]!!.jsonPrimitive.content)
    } }

    private fun assertClean(state: JsonObject, theme: String?, literal: String? = "literal") {
        assertEquals(theme?.let(::JsonPrimitive) ?: JsonNull, state["appearance"])
        assertEquals(literal?.let(::JsonPrimitive) ?: JsonNull, state["literal"])
        assertEquals(JsonNull, state["payload"])
        assertEquals(JsonNull, state["indexed"])
        assertEquals("", state["cookie"]!!.jsonPrimitive.content)
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }

    private fun retentionFile(scope: SourceScope) = File(context.noBackupFilesDir,
        "source-browser-retention/${nativeBrowserProfile(scope.copy(accountGeneration = 0))}.json")

    private inner class Fixture : AutoCloseable {
        val server = MockWebServer()
        private val root = File(context.cacheDir, "native-storage-${UUID.randomUUID()}")
        private val browser = AndroidSourceBrowser(context)
        private val broker = SourceBroker(root.toPath(), browser = browser)
        private val sessions = mutableListOf<SourceSession>()
        private val source = UUID.randomUUID().toString()

        init {
            val dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val address = request.requestUrl!!
                    val set = address.encodedPath == "/set"
                    val theme = JsonPrimitive(address.queryParameter("theme") ?: "dark")
                    val account = address.queryParameter("account") ?: "old"
                    val script = if (set) """localStorage.setItem('appearance',$theme);
                        localStorage.setItem('payload',${JsonPrimitive(account)});localStorage.setItem('__proto__','literal');"""
                        else ""
                    return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").apply {
                        if (set) addHeader("Set-Cookie", "account=$account; Max-Age=3600; HttpOnly; Path=/")
                        setBody("""<html><head><link rel="icon" href="data:,"></head><body><script>
                            $script
                            var opening=indexedDB.open('fixture',1);
                            opening.onupgradeneeded=function(){opening.result.createObjectStore('state');};
                            opening.onsuccess=function(){
                                var db=opening.result, tx=db.transaction('state',${JsonPrimitive(if (set) "readwrite" else "readonly")});
                                var state=tx.objectStore('state');
                                ${if (set) "state.put(" + JsonPrimitive(account) + ",'account');" else ""}
                                var value=state.get('account');
                                tx.oncomplete=function(){window.state={
                                    appearance:localStorage.getItem('appearance'),payload:localStorage.getItem('payload'),
                                    literal:localStorage.getItem('__proto__'),indexed:value.result||null,
                                    ua:navigator.userAgent,webdriver:navigator.webdriver,bridge:typeof window.SourceBrowser,
                                    cookie:${JsonPrimitive(request.getHeader("Cookie").orEmpty())}
                                };db.close();};
                            };
                        </script></body></html>""")
                    }
                }
            }
            server.dispatcher = dispatcher
            server.start()
        }

        fun origin(site: MockWebServer) = sourceOrigin(site.url("/").toString())!!
        fun url(path: String = "/") = server.url(path).toString()

        fun open(generation: Long = 0, source: String = this.source) =
            broker.open(SourceScope("native-storage-tests", source, "legado", generation), listOf(NetworkGrant(url(), true))).apply {
                configureSource(url(), true, browserRead = true,
                    localStorageRetention = LocalStorageRetention(mapOf(origin(server) to listOf("appearance", "__proto__"))))
                sessions += this
            }

        suspend fun seed(session: SourceSession, theme: String = "dark", account: String = "old") {
            render(session, server.url("/set").newBuilder().addQueryParameter("theme", theme).addQueryParameter("account", account).build().toString())
            read(session)
        }

        suspend fun read(session: SourceSession) = render(session, server.url("/read").toString())

        private suspend fun render(session: SourceSession, url: String): JsonObject {
            val result = session.execute(BrokerRequest("storage", url, timeoutMillis = 60000,
                browser = BrowserOptions(script = "window.state || null")))
            assertTrue(result.toString(), result is BrokerResult.Success)
            return Json.parseToJsonElement((result as BrokerResult.Success).response.text()).jsonObject
        }

        override fun close() {
            try {
                sessions.forEach { it.close(); browser.clearAccount(it.scope) }
            } finally {
                broker.close(); server.close()
                sessions.forEach { android.util.AtomicFile(retentionFile(it.scope)).delete() }
                root.deleteRecursively()
            }
        }
    }
}
