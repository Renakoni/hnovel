package hnovel.network

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.TimeUnit

class RequestTraceTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = SourceScope("fixture", "trace", "legado")
    private fun events() = Collections.synchronizedList(mutableListOf<RequestDiagnostic>())
    private fun success(result: BrokerResult) = (result as BrokerResult.Success).response
    private fun SourceSession.observe(events: MutableList<RequestDiagnostic>) = traceRequests { events += it }

    @Test fun precedenceAndLocalTransportEvidenceMatchTheRequestWithoutExposingSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(3) { server.enqueue(MockResponse().setBody("secret-body")) }
            val events = events()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true,
                    mapOf("USER-AGENT" to "origin-secret"))))
                session.configureSource(server.url("/").toString(), true, defaultUserAgent = DESKTOP_USER_AGENT)
                session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS,
                    Json.encodeToString(mapOf("user-agent" to "account-secret", "Authorization" to "token-secret"))))
                session.observe(events)
                val ua = "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0.0.0 secret-ua"
                success(session.execute(BrokerRequest("caller-secret", server.url("/private-path?token=secret-query").toString(),
                    headers = mapOf("uSeR-aGeNt" to ua, "Cookie" to "secret-cookie=1"))))
                val received = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals(ua, received.getHeader("User-Agent"))
                assertEquals("token-secret", received.getHeader("Authorization"))
                val resolved = events.last { it.evidence == RequestEvidence.HeadersResolved }
                assertEquals("secret-cookie=1", received.getHeader("Cookie"))
                assertEquals(CookieDiagnostic(CookieStore.HttpJar, selected = 1, explicit = 1, expired = 0,
                    unmatched = 0, overridden = 0, automaticCapture = true), resolved.cookies)
                assertEquals(listOf(UserAgentSource.SessionDefault, UserAgentSource.OriginGrant, UserAgentSource.AccountLogin,
                    UserAgentSource.RequestHeaders), resolved.userAgentSources)
                val transport = events.single { it.evidence == RequestEvidence.TransportHeaders }
                assertEquals(resolved.userAgent, transport.userAgent)
                assertEquals(130, transport.userAgent?.majorVersion)
                assertEquals(1, events.map { it.requestId }.distinct().size)
                assertTrue(events.all { it.parentRequestId == null })
                assertFalse(received.headers.toString().contains(resolved.requestId))
                val encoded = Json.encodeToString(events.toList())
                listOf(ua, "caller-secret", "private-path", "secret-query", "secret-cookie", "token-secret",
                    "origin-secret", "account-secret", "secret-body").forEach { assertFalse(it, encoded.contains(it)) }
                assertEquals(events.toList(), Json.decodeFromString<List<RequestDiagnostic>>(encoded))
                events.clear()
                success(session.execute(BrokerRequest("same-id", server.url("/account").toString())))
                assertEquals("account-secret", server.takeRequest().getHeader("User-Agent"))
                assertEquals(UserAgentSource.AccountLogin, events.last { it.evidence == RequestEvidence.HeadersResolved }.userAgentSources.last())
                session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS, "{}"))
                events.clear()
                success(session.execute(BrokerRequest("same-id", server.url("/origin").toString())))
                assertEquals("origin-secret", server.takeRequest().getHeader("User-Agent"))
                assertEquals(UserAgentSource.OriginGrant, events.last { it.evidence == RequestEvidence.HeadersResolved }.userAgentSources.last())
            }
        }
    }

    @Test fun disabledAutomaticCaptureDoesNotMeanExplicitCookiesWereNotSelected() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("ok"))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                session.configureSource(server.url("/").toString(), false)
                val events = events(); session.observe(events)
                success(session.execute(BrokerRequest("cookie", server.url("/").toString(),
                    headers = mapOf("Cookie" to "private-session=private-token"))))
                assertEquals("private-session=private-token", server.takeRequest().getHeader("Cookie"))
                val cookies = checkNotNull(events.single {
                    it.evidence == RequestEvidence.HeadersResolved && it.attempt == 0 && it.hop == 0
                }.cookies)
                assertEquals(false, cookies.automaticCapture)
                assertEquals(1, cookies.selected)
                assertEquals(1, cookies.explicit)
                assertFalse(Json.encodeToString(events.toList()).contains("private-"))
            }
        }
    }

    @Test fun transportDefaultIsObservedWithoutInjectingAReplacementHeader() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("ok"))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                val events = events(); session.observe(events)
                success(session.execute(BrokerRequest("r", server.url("/").toString())))
                assertEquals("okhttp/${okhttp3.OkHttp.VERSION}", server.takeRequest().getHeader("User-Agent"))
                assertEquals(listOf(UserAgentSource.TransportDefault), events.first { it.evidence == RequestEvidence.HeadersResolved }.userAgentSources)
                assertEquals(UserAgentFamily.OkHttp, events.single { it.evidence == RequestEvidence.TransportHeaders }.userAgent?.family)
            }
        }
    }

    @Test fun crossOriginRedirectKeepsUaButDropsAccountCredentialsAndUsesOneCorrelationId() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/target")))
            second.enqueue(MockResponse().setBody("done"))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(first, second).map { NetworkGrant(it.url("/").toString(), true) })
                session.configureSource(first.url("/").toString(), true, defaultUserAgent = DESKTOP_USER_AGENT)
                session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS,
                    Json.encodeToString(mapOf("User-Agent" to "account-secret", "Authorization" to "secret"))))
                val events = events(); session.observe(events)
                success(session.execute(BrokerRequest("r", first.url("/start").toString(),
                    headers = mapOf("User-Agent" to "request-secret", "X-Private" to "secret"))))
                assertEquals("secret", first.takeRequest().getHeader("Authorization"))
                val received = second.takeRequest()
                assertNull(received.getHeader("Authorization")); assertNull(received.getHeader("X-Private"))
                assertEquals("request-secret", received.getHeader("User-Agent"))
                assertEquals(listOf(0, 1), events.filter { it.evidence == RequestEvidence.TransportHeaders }.map { it.hop })
                assertEquals(listOf(UserAgentSource.SessionDefault, UserAgentSource.RequestHeaders),
                    events.last { it.evidence == RequestEvidence.HeadersResolved }.userAgentSources)
                assertEquals(1, events.map { it.requestId }.distinct().size)
            }
        } }
    }

    @Test fun cacheAndInlineResponsesNeverClaimNetworkTransport() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("cached"))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                val events = events(); session.observe(events)
                val request = BrokerRequest("constant", server.url("/").toString(), cache = CacheMode.ReadThrough)
                success(session.execute(request))
                val firstId = events.first().requestId
                events.clear()
                assertTrue(success(session.execute(request)).fromCache)
                assertEquals(RequestPath.Cache, events.last().path)
                assertNotEquals(firstId, events.first().requestId)
                assertTrue(events.none { it.evidence == RequestEvidence.TransportHeaders })
                events.clear()
                assertEquals("hello", success(session.execute(BrokerRequest("constant", "data:text/plain;base64,aGVsbG8="))).text())
                assertNull(events.first().reason)
                assertEquals(RequestPath.Inline, events.last().path)
                assertTrue(events.none { it.evidence == RequestEvidence.HeadersResolved || it.evidence == RequestEvidence.TransportHeaders })
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun diagnosticConsumerFailureCannotFailARequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("unchanged"))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                session.traceRequests { error("broken observer") }
                assertEquals("unchanged", success(session.execute(BrokerRequest("r", server.url("/").toString()))).text())
            }
        }
    }

    @Test fun browserChildHttpKeepsParentCorrelationAndDoesNotReenterBrowser() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("mediated"))
            val events = events()
            val browser = BrowserExecutor { session, request, _, guard, route ->
                currentCoroutineContext()[RequestObservation]?.record(RequestEvidence.Selected, RequestPath.MediatedWebView, RequestReason.MediatedFallback)
                session.executeHttp(request, guard, route)
            }
            SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                session.configureSource(server.url("/").toString(), true, browserRead = true, defaultUserAgent = DESKTOP_USER_AGENT)
                session.observe(events)
                assertEquals("mediated", success(session.execute(BrokerRequest("r", server.url("/").toString()))).text())
                val root = events.first()
                assertEquals(RequestReason.SourceBrowserRead, root.reason)
                val child = events.single { it.reason == RequestReason.ExplicitHttp }
                assertEquals(root.requestId, child.parentRequestId)
                assertNotEquals(root.requestId, child.requestId)
                assertEquals(child.requestId, events.single { it.evidence == RequestEvidence.TransportHeaders }.requestId)
                assertEquals(DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun webCookieBrowserUsesProviderDefaultsInsteadOfSourceHeaders() = runBlocking {
        val events = events()
        val browser = BrowserExecutor { _, request, _, _, _ ->
            assertTrue(request.headers.isEmpty())
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
        SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
            val session = broker.open(scope, listOf(NetworkGrant("https://fixture.invalid/")))
            session.configureSource("https://fixture.invalid/", true, defaultUserAgent = DESKTOP_USER_AGENT)
            session.observe(events)
            success(session.execute(BrokerRequest("r", "https://fixture.invalid/", headers = mapOf("User-Agent" to "secret"),
                browser = BrowserOptions(webCookie = "sid=secret", nativeWebsite = true))))
            assertEquals(RequestReason.ExplicitBrowser, events.first().reason)
            val resolved = events.single { it.evidence == RequestEvidence.HeadersResolved }
            assertEquals(RequestReason.WebCookieDefaults, resolved.reason)
            assertEquals(listOf(UserAgentSource.WebViewDefault), resolved.userAgentSources)
            assertNull(resolved.userAgent)
            assertTrue(events.none { it.evidence == RequestEvidence.TransportHeaders })
        }
    }

    @Test fun failedAndCancelledRequestsAreClosedWithoutInventingTransportEvidence() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val browser = BrowserExecutor { _, _, _, _, _ -> entered.complete(Unit); awaitCancellation() }
        SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
            val session = broker.open(scope, listOf(NetworkGrant("https://fixture.invalid/")))
            val events = events(); session.observe(events)
            assertTrue(session.execute(BrokerRequest("r", "https://fixture.invalid/", method = "UNKNOWN")) is BrokerResult.Failure)
            assertEquals(FailureCode.InvalidRequest, events.last().failure)
            events.clear()
            val work = launch { session.execute(BrokerRequest("r", "https://fixture.invalid/", browser = BrowserOptions())) }
            entered.await(); work.cancelAndJoin()
            assertEquals(RequestEvidence.Cancelled, events.last().evidence)
            assertTrue(events.none { it.evidence == RequestEvidence.TransportHeaders })
        }
    }

    @Test fun uaSummaryIsBoundedEvenForCustomAndMalformedValues() {
        assertEquals(UserAgentSummary(UserAgentFamily.Unknown, UserAgentPlatform.Unknown), UserAgentSummary.from(null))
        assertNull(UserAgentSummary.from("Chrome/9999999999999999 secret").majorVersion)
        assertEquals(UserAgentFamily.Other, UserAgentSummary.from("private-client-token").family)
        assertFalse(Json.encodeToString(UserAgentSummary.from("private-client-token")).contains("private-client-token"))
    }
}
