package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.LocalStorageFailure
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

    @Test fun restoredKeysUseCurrentDeclarationAndOriginGrants(): Unit = runBlocking { fixture {
        val declaration = LocalStorageRetention(mapOf(origin(server) to listOf("appearance", "__proto__"),
            origin(other) to listOf("appearance")))
        val old = open(selection = declaration)
        seed(old)
        seed(old, theme = "sepia", site = other)
        old.clearAccount()
        val next = open(1, selection = LocalStorageRetention(declaration.origins.mapValues { listOf("appearance") }),
            grants = listOf(NetworkGrant(url(), true)))
        assertClean(read(next), "dark", literal = null)
        next.close()
        val revised = open(1, selection = declaration)
        assertEquals(JsonNull, read(revised, other)["appearance"])
        assertEquals(JsonNull, read(revised)["literal"])
    } }

    @Test fun repeatedOldCleanupCannotOverwriteANewerAccount(): Unit = runBlocking { fixture {
        val old = open()
        seed(old)
        old.clearAccount()
        val next = open(1)
        assertClean(read(next), "dark")
        seed(next, theme = "light", account = "new")
        old.clearAccount()
        assertEquals("account=new", read(next)["cookie"]!!.jsonPrimitive.content)
        next.clearAccount()
        old.clearAccount()
        assertClean(read(open(2)), "light")
    } }

    @Test fun cancellingFirstNavigationKeepsTheHandoffForAnotherAccount(): Unit = runBlocking { fixture {
        val old = open()
        seed(old)
        old.clearAccount()
        val next = open(1)
        coroutineScope {
            val pending = async { next.execute(BrokerRequest("cancel", url("/wait"), timeoutMillis = 60000,
                browser = BrowserOptions(script = "null"))) }
            withTimeout(20000) { waitStarted.await() }
            pending.cancelAndJoin()
        }
        next.clearAccount()
        assertClean(read(open(2)), "dark")
    } }

    @Test fun quotaFailureStillClearsTheRetiredAuthentication(): Unit = runBlocking { fixture {
        val old = open()
        seed(old, large = true)
        val failure = runCatching { old.clearAccount() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is LocalStorageFailure)
        assertEquals(FailureCode.StorageQuota, (failure as LocalStorageFailure).code)
        assertClean(read(open(1)), null, literal = null)
    } }

    @Test fun failedHandoffWriteLeavesTheNewAccountCleanAndCanBeRetried(): Unit = runBlocking { fixture {
        val old = open()
        seed(old)
        old.clearAccount()
        val current = open(1)
        read(current)
        seed(current, theme = "light", account = "new")
        val blockedWrite = File(retentionFile(current.scope).path + ".new")
        check(blockedWrite.mkdir())
        File(blockedWrite, "fixture").writeText("block atomic replacement")
        val next: SourceSession
        try {
            assertNotNull(runCatching { current.clearAccount() }.exceptionOrNull())
            next = open(2)
            assertEquals(BrokerResult.Failure(RequestStage.Storage, FailureCode.StorageUnavailable),
                next.execute(BrokerRequest("blocked", url("/read"))))
        } finally { blockedWrite.deleteRecursively() }
        assertClean(read(next), null, literal = null)
        seed(next, theme = "blue", account = "newer")
        current.clearAccount()
        assertEquals("account=newer", read(next)["cookie"]!!.jsonPrimitive.content)
        next.clearAccount()
        assertClean(read(open(3)), "blue")
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
        val other = MockWebServer()
        private val root = File(context.cacheDir, "native-storage-${UUID.randomUUID()}")
        private val browser = AndroidSourceBrowser(context)
        private val broker = SourceBroker(root.toPath(), browser = browser)
        private val sessions = mutableListOf<SourceSession>()
        private val source = UUID.randomUUID().toString()
        val waitStarted = CompletableDeferred<Unit>()

        init {
            val dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val address = request.requestUrl!!
                    val set = address.encodedPath == "/set"
                    if (address.encodedPath == "/wait") waitStarted.complete(Unit)
                    val theme = JsonPrimitive(if (address.queryParameter("large") == "true") "x".repeat(17 * 1024)
                        else address.queryParameter("theme") ?: "dark")
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
            server.dispatcher = dispatcher; other.dispatcher = dispatcher
            server.start(); other.start()
        }

        fun origin(site: MockWebServer) = sourceOrigin(site.url("/").toString())!!
        fun url(path: String = "/") = server.url(path).toString()

        fun open(generation: Long = 0, source: String = this.source,
            selection: LocalStorageRetention = LocalStorageRetention(mapOf(origin(server) to listOf("appearance", "__proto__"))),
            grants: List<NetworkGrant> = listOf(NetworkGrant(url(), true), NetworkGrant(other.url("/").toString(), true))) =
            broker.open(SourceScope("native-storage-tests", source, "legado", generation), grants).apply {
                configureSource(url(), true, browserRead = true, localStorageRetention = selection)
                sessions += this
            }

        suspend fun seed(session: SourceSession, theme: String = "dark", account: String = "old", site: MockWebServer = server, large: Boolean = false) {
            render(session, site.url("/set").newBuilder().addQueryParameter("theme", theme).addQueryParameter("account", account)
                .addQueryParameter("large", large.toString()).build().toString())
            read(session, site)
        }

        suspend fun read(session: SourceSession, site: MockWebServer = server) = render(session, site.url("/read").toString())

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
                broker.close(); server.close(); other.close()
                sessions.forEach { android.util.AtomicFile(retentionFile(it.scope)).delete() }
                root.deleteRecursively()
            }
        }
    }
}
