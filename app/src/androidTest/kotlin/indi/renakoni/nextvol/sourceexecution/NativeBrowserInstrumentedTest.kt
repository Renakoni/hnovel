package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity
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
            .apply { configureSource(server.url("/").toString(), true, browserRead = true, defaultUserAgent = DESKTOP_USER_AGENT); scopes += this }

    private suspend fun render(session: SourceSession, url: String, script: String = "document.title", interactive: Boolean = false): BrokerResponse {
        val result = session.execute(BrokerRequest("render", url, timeoutMillis = 60000,
            browser = BrowserOptions(script = script, interactive = interactive)))
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }

    @Test fun desktopUserAgentReachesNativeNavigationAndClientHints(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <html><head><link rel="icon" href="data:,"></head><body><script>
                window.answer=JSON.stringify({http:${JsonPrimitive(request.getHeader("User-Agent"))},
                    dom:navigator.userAgent,hints:navigator.userAgentData ? navigator.userAgentData.toJSON() : null});
                </script></body></html>
            """.trimIndent())
        }
        val page = Json.parseToJsonElement(render(session(broker, server), server.url("/book").toString(), "window.answer").text()).jsonObject
        val ua = page.getValue("http").jsonPrimitive.content
        assertTrue(ua, ua.contains("Windows NT"))
        assertEquals(ua, page.getValue("dom").jsonPrimitive.content)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            val hints = page.getValue("hints").jsonObject
            assertEquals("Windows", hints.getValue("platform").jsonPrimitive.content)
            assertFalse(hints.getValue("mobile").jsonPrimitive.boolean)
            assertFalse(hints.getValue("brands").jsonArray.any { it.jsonObject.getValue("brand").jsonPrimitive.content == "Android WebView" })
        }
    } }

    @Test fun headerOnlyCloudflareChallengeOffersVerificationInsteadOfANetworkError(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "text/html").setHeader("cf-mitigated", "challenge")
                .setBody("<html><head><title>Verification</title><link rel='icon' href='data:,'></head><body>Check</body></html>")
        }
        val result = session(broker, server).execute(BrokerRequest("challenge", server.url("/book").toString(), timeoutMillis = 30000))
        assertTrue(result.toString(), result is BrokerResult.Failure)
        val failure = result as BrokerResult.Failure
        assertEquals(FailureCode.BrowserRequired, failure.code)
        assertEquals(BrowserChallengeKind.Cloudflare, failure.challenge)
        assertNotNull(failure.verificationRequest)
    } }

    @Test fun largeRenderedDocumentsCrossThePipeAndRespectTheCallerLimit(): Unit = runBlocking { fixture { broker, server ->
        val text = "文".repeat(600000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><head><link rel='icon' href='data:,'></head><body><article>$text</article></body></html>")
        }
        val account = session(broker, server)
        val request = BrokerRequest("large", server.url("/book").toString(), maxResponseBytes = 2 * 1024 * 1024, timeoutMillis = 60000)
        val result = account.execute(request)
        assertTrue(result.toString(), result is BrokerResult.Success)
        val response = (result as BrokerResult.Success).response
        assertTrue(response.body.size > 1024 * 1024)
        assertEquals(text, org.jsoup.Jsoup.parse(response.text()).selectFirst("article")!!.text())
        assertEquals(FailureCode.ResponseTooLarge, (account.execute(request.copy(maxResponseBytes = 1024 * 1024)) as BrokerResult.Failure).code)
    } }

    @Test fun siteCaptchaRedirectResumesTheOriginalChapterWithItsVerifiedCookies(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            val accepted = java.util.concurrent.atomic.AtomicBoolean()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/chapter/1" -> if (request.getHeader("Cookie").orEmpty().contains("verified=fixture"))
                        MockResponse().setHeader("Content-Type", "text/html").setBody("<title>free chapter</title><article>Readable</article>")
                    else MockResponse().setResponseCode(307).setHeader("Location", "/signup/man_machine_verify")
                    "/signup/man_machine_verify" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><title>验证码</title><form id='J_ManMachineVerify' action='/accepted'></form>
                        ${if (accepted.get()) "<script>document.forms[0].submit()</script>" else ""}</html>
                    """.trimIndent())
                    "/accepted" -> MockResponse().setResponseCode(302).setHeader("Location", "/chapter/1")
                        .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val account = session(broker, server)
            account.configureSource(server.url("/").toString(), false, browserRead = true,
                defaultUserAgent = DESKTOP_USER_AGENT)
            val target = server.url("/chapter/1").toString()
            val blocked = account.execute(BrokerRequest("read", target)) as BrokerResult.Failure
            assertEquals(BrowserChallengeKind.SiteVerification, blocked.challenge)
            assertEquals(target, blocked.verificationRequest!!.url)
            accepted.set(true)
            assertEquals("free chapter", render(account, target, interactive = true).text())
            assertEquals("Readable", render(account, target, "document.querySelector('article').textContent").text())
        } }
    }

    @Test fun nativeIframeFetchCookiesAndFinalUrlArePreserved() = iframeCookies(true)

    @Test fun legadoWebViewOptionUsesNativeWebsiteWithoutRequiringBrowserRead() = iframeCookies(false)

    private fun iframeCookies(browserRead: Boolean): Unit = runBlocking { fixture { broker, server ->
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
        val account = session(broker, server)
        account.configureSource(server.url("/").toString(), true, browserRead = browserRead)
        val response = if (browserRead) render(account, server.url("/start").toString(), "window.answer || null") else {
            val rule = server.url("/start").toString() + "," + buildJsonObject {
                put("webView", true); put("webJs", "window.answer || null")
            }
            val compiled = RequestCompiler().compile("render", rule, server.url("/").toString()) as CompiledRequest.Ready
            val result = account.execute(compiled.request.copy(timeoutMillis = 60000))
            assertTrue(result.toString(), result is BrokerResult.Success)
            (result as BrokerResult.Success).response
        }
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
            val accepted = java.util.concurrent.atomic.AtomicBoolean()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/protected" -> when {
                        request.getHeader("Cookie").orEmpty().contains("verified=fixture") ->
                            MockResponse().setHeader("Content-Type", "text/html").setBody("<title>session-resumed</title>")
                        accepted.get() -> MockResponse().setResponseCode(302).setHeader("Location", "/accepted")
                        else -> MockResponse().setResponseCode(302).setHeader("Location", "/antibot")
                    }
                    "/antibot" -> MockResponse().setHeader("Content-Type", "text/html")
                        .setBody("<html><title>Just a moment</title></html>")
                    "/accepted" -> MockResponse().setHeader("Content-Type", "text/html")
                        .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                        .setBody("<html><title>accepted</title></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val account = session(broker, server)
            val target = server.url("/protected").toString()
            val blocked = account.execute(BrokerRequest("read", target, timeoutMillis = 60000))
            assertEquals(FailureCode.BrowserRequired, (blocked as BrokerResult.Failure).code)
            assertEquals(target, (account.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as StorageResult.Value).value)
            accepted.set(true)
            val resumed = withTimeout(30000) {
                render(account, target, "document.title==='accepted' ? document.title : null", interactive = true)
            }
            assertEquals("accepted", resumed.text())
            assertEquals(server.url("/accepted").toString(), resumed.finalUrl)
            assertEquals("session-resumed", render(account, target).text())
        } }
    }

    @Test fun cancellingARequestKeepsPersistentStateWithoutClearingTheAccount(): Unit = runBlocking { fixture { broker, server ->
        val started = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/hold") started.complete(Unit)
                return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").apply {
                    if (path == "/set") addHeader("Set-Cookie", "persistent=fixture; Max-Age=3600; HttpOnly; Path=/")
                    val body = when (path) {
                        "/set" -> "localStorage.setItem('owner','fixture');window.answer='ready'"
                        "/hold" -> "window.answer=null"
                        else -> "window.answer=JSON.stringify({owner:localStorage.getItem('owner'),cookie:${JsonPrimitive(request.getHeader("Cookie").orEmpty())}})"
                    }
                    setBody("<html><head><link rel='icon' href='data:,'></head><body><script>$body</script></body></html>")
                }
            }
        }
        val account = session(broker, server)
        render(account, server.url("/set").toString(), "window.answer || null")
        val pending = launch { render(account, server.url("/hold").toString(), "window.answer || null") }
        withTimeout(20000) { started.await() }
        withTimeout(20000) { pending.cancelAndJoin() }
        val restored = Json.parseToJsonElement(render(account, server.url("/read").toString(), "window.answer || null").text()).jsonObject
        assertEquals("fixture", restored.getValue("owner").jsonPrimitive.content)
        assertEquals("persistent=fixture", restored.getValue("cookie").jsonPrimitive.content)
        assertEquals(0L, account.scope.accountGeneration)
        // Session cookies are deliberately not given an invented persistence guarantee.
    } }

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

    @Test fun publicLoginLinkIsReadableAndActualLoginAndVerificationPagesHaveDistinctKinds(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody(
                when (request.path) {
                    "/login" -> "<html><form><input type='password'></form></html>"
                    "/antibot" -> "<html><title>Site verification</title></html>"
                    else -> "<html><title>Public catalog</title><nav><a href='/login'>Login</a></nav><ul><li>Fixture</li></ul></html>"
                })
        }
        val account = session(broker, server)
        assertEquals("Public catalog", render(account, server.url("/public").toString(), "document.title").text())
        for ((path, kind) in listOf("/login" to BrowserChallengeKind.Login, "/antibot" to BrowserChallengeKind.SiteVerification)) {
            val result = account.execute(BrokerRequest("classification", server.url(path).toString())) as BrokerResult.Failure
            assertEquals(FailureCode.BrowserRequired, result.code)
            assertEquals(kind, result.challenge)
        }
    } }

    @Test fun ordinaryHttpStillReturnsRawStatusAndDoesNotExecuteScripts(): Unit = runBlocking { fixture { broker, server ->
        server.enqueue(MockResponse().setResponseCode(202).setHeader("X-Fixture", "raw").setBody("<script>fetch('/unexpected')</script>"))
        val session = broker.open(SourceScope("native-tests", UUID.randomUUID().toString(), "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
        val response = (session.execute(BrokerRequest("http", server.url("/").toString())) as BrokerResult.Success).response
        assertEquals(ResponseKind.Http, response.kind); assertEquals(202, response.status)
        assertEquals(listOf("raw"), response.headers.entries.firstOrNull { it.key.equals("X-Fixture", true) }?.value)
        assertEquals(1, server.requestCount)
    } }

    @Test fun unsupportedRequestShapesFailBeforeSendingAnyNetworkRequest(): Unit = runBlocking { fixture { broker, server ->
        val account = session(broker, server)
        val request = BrokerRequest("unsupported", server.url("/").toString())
        for (input in listOf(request.copy(method = "POST", body = "field=value"),
            request.copy(followRedirects = false), request.copy(responseAsHex = true),
            request.copy(cache = CacheMode.Only), request.copy(headers = mapOf("Cookie" to "injected=fixture")),
            request.copy(browser = BrowserOptions(html = "<html>synthetic</html>")))) {
            assertEquals(BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest), account.execute(input))
        }
        assertEquals(0, server.requestCount)
    } }

    @Test fun backgroundDocumentHasAnActualViewport(): Unit = runBlocking { fixture { broker, server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            "<meta name='viewport' content='width=device-width,initial-scale=1'><div style='width:50vw' id='half'></div>"))
        val result = Json.parseToJsonElement(render(session(broker, server), server.url("/").toString(),
            "JSON.stringify({width:innerWidth,height:innerHeight,half:document.getElementById('half').getBoundingClientRect().width})").text()).jsonObject
        val width = result.getValue("width").jsonPrimitive.double
        assertTrue(width > 0)
        assertTrue(result.getValue("height").jsonPrimitive.double > 0)
        assertEquals(width / 2, result.getValue("half").jsonPrimitive.double, 1.0)
    } }

    @Test fun sourcePacingSurvivesForegroundWaitAndLeavesPageResourcesNative(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val holding = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    starts[path] = System.nanoTime() / 1_000_000
                    if (path == "/hold") holding.complete(Unit)
                    val body = when {
                        path == "/hold" -> "<script>setTimeout(function(){window.answer='ready'},3500)</script>"
                        path.startsWith("/page") -> """<iframe src="/frame$path"></iframe><script>
                            fetch('/resource$path').then(r=>r.text()).then(function(){window.answer='ready'})
                            </script>"""
                        else -> "<title>ready</title>"
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                        .setBody("<html><head><link rel='icon' href='data:,'></head><body>$body</body></html>")
                }
            }
            val account = session(broker, server)
            render(account, server.url("/warm").toString())
            account.configureSource(server.url("/").toString(), true, browserRead = true, concurrentRate = "1/2000")
            val foreground = async { render(account, server.url("/hold").toString(), "window.answer || null", interactive = true) }
            withTimeout(15000) { holding.await() }
            val cancelled = launch { render(account, server.url("/cancelled").toString()) }
            val pages = (1..2).map { index -> async {
                render(account, server.url("/page$index").toString(), "window.answer || null")
            } }
            delay(100)
            cancelled.cancelAndJoin()
            assertTrue(account.execute(BrokerRequest("api", server.url("/api").toString(), kind = ResourceKind.Api)) is BrokerResult.Success)
            foreground.await()
            pages.awaitAll()
            val dispatches = listOf("/hold", "/api", "/page1", "/page2").map(starts::getValue).sorted()
            val gaps = dispatches.zipWithNext { a, b -> b - a }
            assertTrue("Source request gaps: $gaps", gaps.all { it >= 1700 })
            assertFalse(starts.containsKey("/cancelled"))
            val resourceGaps = (1..2).map { index ->
                val page = starts.getValue("/page$index")
                maxOf(starts.getValue("/resource/page$index"), starts.getValue("/frame/page$index")) - page
            }
            assertTrue("Native resource gaps: $resourceGaps", resourceGaps.all { it < 1500 })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("sourcePacing", "requestGapsMillis=$gaps;resourceGapsMillis=$resourceGaps;cancelledRequests=0")
            })
        } }
    }

    /** Explicit local URL allows the same fixture to be run in Chrome and reference MD3. */
    @Test fun recordLocalEnvironment(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("browserProbeUrl")
        assumeTrue(url != null)
        require(url!!.startsWith("http://127.0.0.1:18766/probe") || url.startsWith("http://127.0.0.1:18767/consistency"))
        val root = File(context.cacheDir, "native-probe-${UUID.randomUUID()}")
        val activity = if (args.getString("foregroundProbe") == "true") ActivityScenario.launch(BrowserTestHostActivity::class.java) else null
        try {
        SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
            val session = broker.open(SourceScope("browser-probe", UUID.randomUUID().toString(), "legado"),
                listOf(NetworkGrant(sourceOrigin(url)!!, true)))
            session.configureSource(sourceOrigin(url)!!, true, args.getString("nativeProbe") == "true")
            try {
                val result = render(session, url,
                    if (url.contains("/consistency")) "window.consistencyDone ? JSON.stringify(window.consistencyResult) : null"
                    else "window.probeDone ? JSON.stringify(window.probeResult) : null",
                    interactive = args.getString("foregroundProbe") == "true")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("browserProbe", result.text()) })
            } finally { session.clearAccount() }
        }
        } finally { activity?.close() }
        root.deleteRecursively()
    }
}
