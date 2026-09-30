package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PixivAuthenticationTest {
    private val original = Json.parseToJsonElement(File(
        "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
        .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
    private val token = "a".repeat(32)
    private val userAgent = "Test WebView-UA \"quoted\""
    private val listBody = """{"error":false,"body":{"page":{"recommend":{"ids":[]}},"thumbnails":{"novel":[]}}}"""

    private fun definition(fixture: RuleSourceFixture): JsonObject {
        val base = fixture.server.url("/").toString().removeSuffix("/")
        val local = Json.parseToJsonElement(original.toString()
            .replace("https://www.pixiv.net", base).replace("https://accounts.pixiv.net", "$base/accounts")).jsonObject
        return JsonObject(local + ("exploreUrl" to JsonPrimitive("""@js:
            var testSettings=setDefaultSettings();
            testSettings.IPDirect=false;testSettings.FAST=true;testSettings.DEBUG=false;
            testSettings.SHOW_BOOKMARKS_PUBLIC=true;testSettings.SHOW_BOOKMARKS_PRIVATE=true;
            putInCacheObject('pixivSettings',testSettings);
        """.trimIndent() + "\n" + local.getValue("exploreUrl").jsonPrimitive.content.removePrefix("@js:"))))
    }

    private fun session(fixture: RuleSourceFixture, source: RuleSource) = fixture.broker.open(
        SourceScope("rules", source.definition.sourceId, source.definition.profile),
        listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
    private fun cached(session: SourceSession, key: String) =
        (session.read(StorageRequest(StorageArea.Cache, "value:$key")) as StorageResult.Value).value
    private fun cache(session: SourceSession, key: String, value: String) {
        assertTrue(session.write(StorageRequest(StorageArea.Cache, "value:$key", value)) is StorageResult.Value)
    }
    private fun browser(block: (SourceSession, BrokerRequest) -> Unit = { _, _ -> error("Unexpected browser") }) = object : BrowserExecutor {
        override suspend fun defaultUserAgent() = userAgent
        override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
            guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
            block(session, request)
            return BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "<html>Done</html>".toByteArray(),
                "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
    }

    @Test fun anonymousVisitsNeverCreateAnAccountFromGuestCookiesOrLegacyCsrf() = runBlocking {
        for (legacy in listOf(false, true)) RuleSourceFixture(browser()).use { fixture ->
            val homeRequests = AtomicInteger()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.path == "/") {
                        homeRequests.incrementAndGet()
                        """<html>token\":\"$token</html>"""
                    } else listBody
                    return MockResponse().setBody(body)
                        .addHeader("Set-Cookie", "first_visit_datetime_pc=20260927; Path=/")
                        .addHeader("Set-Cookie", "PHPSESSID=0_guest; Path=/")
                }
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val session = session(fixture, source)
                if (legacy) {
                    cache(session, "pixivCsrfToken", token)
                    cache(session, "pixivUid", "20260927")
                    cache(session, "pixivCookie", "first_visit_datetime_pc=20260927; PHPSESSID=0_guest")
                }
                source.openDiscovery("guest").catalog()
                repeat(3) { source.discovery("/ajax/top/novel?mode=all&lang=zh") }
                for (key in listOf("pixivUid", "pixivCookie", "pixivCsrfToken")) assertNull("legacy=$legacy key=$key", cached(session, key))
                assertEquals("Guest discovery must not fetch a CSRF page", 0, homeRequests.get())
            }
        }
    }

    @Test fun namedSessionCorrectsLegacyUidAndCredentialRotationRefreshesCsrf() = runBlocking {
        RuleSourceFixture(browser()).use { fixture ->
            var credential = "12345_first"
            var csrf = token
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .addHeader("Set-Cookie", "first_visit_datetime_pc=20260927; Path=/")
                    .addHeader("Set-Cookie", "PHPSESSID=$credential; Path=/")
                    .setBody(if (request.path == "/") """<html>token\":\"$csrf</html>""" else listBody)
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val session = session(fixture, source)
                cache(session, "pixivUid", "20260927")
                cache(session, "pixivCookie", "first_visit_datetime_pc=20260927; PHPSESSID=99999_old")
                cache(session, "pixivCsrfToken", "old-token")
                source.openDiscovery("account").catalog()
                source.discovery("/ajax/top/novel?mode=all&lang=zh")
                assertEquals("12345", cached(session, "pixivUid"))
                assertEquals(token, cached(session, "pixivCsrfToken"))
                credential = "12345_second"
                csrf = "b".repeat(32)
                source.discovery("/ajax/top/novel?mode=all&lang=zh")
                assertEquals("12345", cached(session, "pixivUid"))
                assertEquals(csrf, cached(session, "pixivCsrfToken"))
            }
        }
    }

    @Test fun catalogUsesLiveCookiesAfterCacheExpiryAccountChangesOrLogout() = runBlocking {
        for (storedCookie in listOf(null, "PHPSESSID=99999_stale"))
            for (currentCookie in listOf("PHPSESSID=12345_current", "PHPSESSID=0_guest", ""))
                RuleSourceFixture(browser()).use { fixture ->
                    fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                        val session = session(fixture, source)
                        if (currentCookie.isNotEmpty()) session.setCookie(fixture.server.url("/").toString(), currentCookie)
                        if (storedCookie != null) cache(session, "pixivCookie", storedCookie)
                        cache(session, "pixivUid", "99999")
                        cache(session, "pixivCsrfToken", "stale-token")
                        cache(session, "pixivHeaders", """{"Cookie":"PHPSESSID=99999_stale"}""")
                        val discovery = source.openDiscovery("restored-account")
                        val catalog = discovery.catalog()
                        val authenticated = currentCookie == "PHPSESSID=12345_current"
                        assertEquals("stored=$storedCookie current=$currentCookie",
                            if (authenticated) "12345" else null, cached(session, "pixivUid"))
                        assertEquals(if (authenticated) currentCookie else null, cached(session, "pixivCookie"))
                        assertNull(cached(session, "pixivCsrfToken"))
                        assertNull(cached(session, "pixivHeaders"))
                        if (authenticated) assertNotNull(discovery.concurrentPreviews(
                            catalog.rows.filter { it.url.contains("/novels/bookmarks?") }.map { it.url }, emptyMap()))
                        assertEquals("Account restoration must not require a response hook", 0, fixture.server.requestCount)
                    }
                }
    }

    @Test fun readingActionsRebuildHeadersAfterLoginChangesCredentials() = runBlocking {
        val post = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        RuleSourceFixture(browser { session, request ->
            session.setCookie(request.url.substringBefore("/accounts/"), "PHPSESSID=12345_fresh", replace = true)
        }).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/ajax/settings/self" -> {
                        val authenticated = request.getHeader("Cookie").orEmpty().contains("PHPSESSID=12345_fresh")
                        MockResponse().setResponseCode(if (authenticated) 200 else 401)
                            .setBody(if (authenticated) """{"error":false,"body":{}}""" else """{"error":true,"body":{}}""")
                    }
                    "/ajax/novels/bookmarks/add" -> {
                        post.set(request)
                        MockResponse().setBody("""{"error":false,"body":{}}""")
                    }
                    else -> MockResponse().setBody("""<html>token\":\"$token</html>""")
                }
            }
            fixture.source(profile = EXTENSION_PROFILE) { JsonObject(definition(fixture) +
                ("loginUi" to JsonPrimitive("""[
                    {"name":"login","type":"button","action":"login()"},
                    {"name":"post","type":"button","action":"getPostBody('${fixture.server.url("/ajax/novels/bookmarks/add")}', '{}')"}
                ]""")))
            }.use { source ->
                val session = session(fixture, source)
                session.setCookie(fixture.server.url("/").toString(), "PHPSESSID=11111_expired")
                cache(session, "pixivCookie", "PHPSESSID=11111_expired")
                cache(session, "pixivUid", "11111")
                cache(session, "pixivCsrfToken", "expired-token")
                cache(session, "pixivHeaders", """{"Cookie":"PHPSESSID=11111_expired","x-csrf-token":"expired-token"}""")
                source.openDiscovery("reading-action").catalog()
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    for (name in listOf("login", "post"))
                        panel.login(form.values, form.fields.single { it.name == name }.id, form.id)
                }
                assertTrue(post.get().getHeader("Cookie").orEmpty().contains("PHPSESSID=12345_fresh"))
                assertEquals(token, post.get().getHeader("x-csrf-token"))
                assertEquals(userAgent, post.get().getHeader("User-Agent"))
            }
        }
    }

    @Test fun expiredSessionsCanOpenLoginAndRefreshCredentialsUsingValidBrowserHeaders() = runBlocking {
        val openings = AtomicInteger()
        val selfRequests = AtomicInteger()
        RuleSourceFixture(browser { session, request ->
            openings.incrementAndGet()
            assertTrue(request.url.endsWith("/accounts/login"))
            assertEquals(userAgent, request.headers.entries.single { it.key.equals("User-Agent", true) }.value)
            assertTrue(request.headers.keys.none { it.equals("Cookie", true) })
            session.setCookie(request.url.substringBefore("/accounts/"), "first_visit_datetime_pc=20260927; PHPSESSID=12345_fresh", replace = true)
        }).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/ajax/settings/self") {
                        selfRequests.incrementAndGet()
                        val authenticated = request.getHeader("Cookie").orEmpty().contains("PHPSESSID=12345_fresh")
                        return MockResponse().setResponseCode(if (authenticated) 200 else 401)
                            .setBody(if (authenticated) """{"error":false,"body":{}}""" else """{"error":true,"body":{}}""")
                    }
                    return MockResponse().setBody("""<html>token\":\"$token</html>""")
                }
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val session = session(fixture, source)
                session.setCookie(fixture.server.url("/").toString(), "PHPSESSID=11111_expired")
                cache(session, "pixivCookie", "PHPSESSID=11111_expired")
                cache(session, "pixivUid", "11111")
                cache(session, "pixivCsrfToken", "expired-token")
                source.openDiscovery("login").catalog()
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    val action = form.fields.single { it.name.contains("登录账号") }
                    panel.login(form.values, action.id, form.id)
                }
                assertEquals(1, openings.get())
                assertEquals(2, selfRequests.get())
                assertEquals("12345", cached(session, "pixivUid"))
                assertEquals(token, cached(session, "pixivCsrfToken"))
            }
        }
    }

    @Test fun rejectedGuestDiscoveryAndSearchKeepTheirHttpStatusAndExistingCookies() = runBlocking {
        RuleSourceFixture(browser()).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
                    .setBody("""{"error":true,"body":{}}""")
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val session = session(fixture, source)
                val url = fixture.server.url("/").toString()
                session.setCookie(url, "PHPSESSID=0_guest; visitor=preserved")
                source.openDiscovery("denied").catalog()
                for (search in listOf(false, true)) {
                    val failure = runCatching {
                        if (search) source.search("fixture") else source.discovery("/ajax/top/novel?mode=r18&lang=zh")
                    }.exceptionOrNull() as? SourceContentException
                    assertEquals(failure?.diagnostic.toString(), ContentError.Network, failure?.code)
                    assertEquals(401, failure?.httpStatus)
                }
                assertTrue(session.cookie(url).contains("visitor=preserved"))
                assertTrue(session.cookie(url).contains("PHPSESSID=0_guest"))
            }
        }
    }

    @Test fun accountSettingsUseBrowserHeadersAndPreserveTheCookieJar() = runBlocking {
        val openings = AtomicInteger()
        RuleSourceFixture(browser { session, request ->
            openings.incrementAndGet()
            assertTrue(request.url.endsWith("/settings/viewing"))
            assertEquals(userAgent, request.headers.entries.single { it.key.equals("User-Agent", true) }.value)
            assertTrue(request.headers.keys.none { it.equals("Cookie", true) })
            assertTrue(session.cookie(request.url).contains("PHPSESSID=12345_saved"))
        }).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val session = session(fixture, source)
                session.setCookie(fixture.server.url("/").toString(), "PHPSESSID=12345_saved")
                cache(session, "pixivCookie", "PHPSESSID=12345_saved")
                cache(session, "pixivHeaders", """{"Cookie":"PHPSESSID=12345_saved"}""")
                source.openDiscovery("settings").catalog()
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    val action = form.fields.single { it.action?.contains("startPixivSettings") == true }
                    panel.login(form.values, action.id, form.id)
                }
                assertEquals(1, openings.get())
            }
        }
    }
}
