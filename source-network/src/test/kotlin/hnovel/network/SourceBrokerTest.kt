package hnovel.network

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SourceBrokerTest {
    @get:Rule val directory = TemporaryFolder()
    private fun MockWebServer.recorded(): RecordedRequest =
        checkNotNull(takeRequest(3, TimeUnit.SECONDS)) { "Expected an HTTP request" }
    private fun scope(id: String = "a", profile: String = "legado", generation: Long = 0) = SourceScope("fixture", id, profile, generation)
    private fun grant(url: HttpUrl, privateAddresses: Boolean = true, headers: Map<String, String> = emptyMap()) =
        NetworkGrant(url.newBuilder().encodedPath("/").query(null).build().toString(), privateAddresses, headers)
    private fun request(url: HttpUrl, cache: CacheMode = CacheMode.Disabled) = BrokerRequest("r", url.toString(), cache = cache)
    private fun success(result: BrokerResult): BrokerResponse {
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }

    @Test fun sameDomainDoesNotShareCookiesCacheProfilesOrResponseArrays() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val a = broker.open(scope(), listOf(grant(server.url("/"))))
                val b = broker.open(scope("b"), listOf(grant(server.url("/"))))
                val profile = broker.open(scope(profile = "other"), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse().setBody("login").addHeader("Set-Cookie", "auth=A; Path=/"))
                success(a.execute(request(server.url("/login"))))
                server.recorded()
                server.enqueue(MockResponse().setBody("A-content"))
                val cached = success(a.execute(request(server.url("/book"), CacheMode.ReadThrough)))
                assertEquals("auth=A", server.recorded().getHeader("Cookie"))
                cached.body[0] = '!'.code.toByte()
                assertEquals("A-content", success(a.execute(request(server.url("/book"), CacheMode.Only))).text())
                assertEquals(BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss), b.execute(request(server.url("/book"), CacheMode.Only)))
                for (session in listOf(b, profile)) {
                    server.enqueue(MockResponse().setBody("other"))
                    success(session.execute(request(server.url("/book"))))
                    assertNull(server.recorded().getHeader("Cookie"))
                }
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun headersCookiePriorityAndResponseCharsetAreAppliedAtActualRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"), headers = mapOf("X-Token" to "account"))))
                server.enqueue(MockResponse().addHeader("Set-Cookie", "name=jar; Path=/").addHeader("Set-Cookie", "second=jar; Path=/"))
                session.execute(request(server.url("/login"))); server.recorded()
                server.enqueue(MockResponse().setBody(okio.Buffer().write("校园".toByteArray(charset("GB18030"))))
                    .addHeader("Content-Type", "text/plain; charset=GB18030"))
                val result = success(session.execute(request(server.url("/book")).copy(headers = mapOf("Cookie" to "name=explicit", "X-Token" to "request"))))
                assertEquals("校园", result.text())
                val recorded = server.recorded()
                assertEquals("request", recorded.getHeader("X-Token"))
                assertEquals("name=explicit; second=jar", recorded.getHeader("Cookie"))
            }
        }
    }

    @Test(timeout = 10000) fun cookieChangesPreventLateRequestsFromRepopulatingThePreviousCacheGeneration() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/before" -> MockResponse().addHeader("Set-Cookie", "auth=before; Path=/")
                    "/after" -> MockResponse().addHeader("Set-Cookie", "auth=after; Path=/")
                    else -> { started.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody("before") }
                }
            }
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                session.execute(request(server.url("/before")))
                val pending = async { session.execute(request(server.url("/book"), CacheMode.ReadThrough)) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) }
                    session.execute(request(server.url("/after")))
                    release.countDown()
                    success(pending.await())
                    assertEquals(BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss), session.execute(
                        request(server.url("/book"), CacheMode.Only).copy(headers = mapOf("Cookie" to "auth=before"))))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun headerCacheKeysUseUnambiguousStructuredEncoding() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse().setBody("first"))
                success(session.execute(request(server.url("/"), CacheMode.ReadThrough).copy(headers = mapOf("X" to "a", "Y" to "b"))))
                assertEquals(BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss), session.execute(
                    request(server.url("/"), CacheMode.Only).copy(headers = mapOf("X" to "a], Y=[b"))))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun crossOriginRedirectDropsCallerSecretsAndUsesOnlyTargetCredentials() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            val target = second.url("/next").newBuilder().host("127.0.0.1").build()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(first.url("/")), grant(target, headers = mapOf("X-Target" to "target"))))
                first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.toString()))
                second.enqueue(MockResponse().setBody("done"))
                val result = success(session.execute(request(first.url("/start")).copy(headers = mapOf("Authorization" to "secret", "Cookie" to "auth=explicit", "X-Api-Key" to "hidden"))))
                assertEquals(1, result.redirects)
                assertEquals("done", result.text())
                assertEquals("secret", first.recorded().getHeader("Authorization"))
                val recorded = second.recorded()
                assertNull(recorded.getHeader("Authorization")); assertNull(recorded.getHeader("Cookie")); assertNull(recorded.getHeader("X-Api-Key"))
                assertEquals("target", recorded.getHeader("X-Target"))
            }
        } }
    }

    @Test fun redirectsCannotReachUnapprovedPrivateTargetsOrForwardPostBodies() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            val target = second.url("/")
            SourceBroker(directory.root.toPath()).use { broker ->
                val a = broker.open(scope(), listOf(grant(first.url("/"))))
                first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.toString()))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.OriginDenied), a.execute(request(first.url("/"))))
                val b = broker.open(scope("b"), listOf(grant(first.url("/")), grant(target, false)))
                first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.toString()))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.AddressDenied), b.execute(request(first.url("/"))))
                val c = broker.open(scope("c"), listOf(grant(first.url("/")), grant(target)))
                first.enqueue(MockResponse().setResponseCode(307).addHeader("Location", target.toString()))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.RedirectBodyDenied), c.execute(request(first.url("/")).copy(method = "POST", body = "secret")))
                assertEquals(0, second.requestCount)
            }
        } }
    }

    @Test fun dnsChecksTheAddressesUsedByTheConnectionAndRejectsRebindingIpv6AndLiterals() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/").newBuilder().host("source.invalid").build()
            val calls = AtomicInteger()
            val resolver = Dns { calls.incrementAndGet(); listOf(InetAddress.getByName("127.0.0.1")) }
            SourceBroker(directory.root.toPath(), resolver).use { broker ->
                val session = broker.open(scope(), listOf(grant(url, false)))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.AddressDenied), session.execute(request(url)))
                assertEquals(1, calls.get())
                val literal = server.url("/").newBuilder().host("::1").build()
                val ipv6 = broker.open(scope("ipv6"), listOf(grant(literal, false)))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.AddressDenied), ipv6.execute(request(literal)))
                assertEquals(1, calls.get())
                assertEquals(0, server.requestCount)
            }
            var lookups = 0
            val policy = NetworkPolicy(listOf(grant(url, false)), Dns {
                listOf(InetAddress.getByName(if (lookups++ == 0) "93.184.216.34" else "::ffff:127.0.0.1"))
            })
            assertEquals(1, policy.dns(url).lookup(url.host).size)
            assertThrows(BrokerFailure::class.java) { policy.dns(url).lookup(url.host) }
            for (ip in listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "::1", "fc00::1", "fe80::1", "2002:7f00:1::", "2001:db8::1")) {
                assertFalse(ip, NetworkPolicy.isPublicAddress(InetAddress.getByName(ip)))
            }
            assertTrue(NetworkPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        }
    }

    @Test fun persistentConfigAccountAndRequestVariablesHaveDifferentLifetimesAndQuotas() {
        val limits = BrokerLimits(maxStorageBytes = 16, maxStorageEntries = 2)
        SourceBroker(directory.root.toPath(), limits = limits).use { broker ->
            val a = broker.open(scope(), emptyList())
            assertEquals(StorageResult.Value("config"), a.write(StorageRequest(StorageArea.Config, "../../outside", "config")))
            a.write(StorageRequest(StorageArea.Account, "name", "A"))
            assertEquals(StorageResult.Failure(FailureCode.StorageQuota), a.write(StorageRequest(StorageArea.Config, "large", "x".repeat(17))))
            assertEquals(StorageResult.Value("config"), a.read(StorageRequest(StorageArea.Config, "../../outside")))
            assertFalse(directory.root.parentFile.resolve("outside").exists())
            val one = a.newVariables(mapOf("name" to "one"))
            val two = a.newVariables(mapOf("name" to "two"))
            one.put("name", "changed")
            assertEquals("two", two.get("name"))
            val newer = broker.open(scope(generation = 1), emptyList())
            assertTrue(a.closed)
            assertEquals(StorageResult.Value("config"), newer.read(StorageRequest(StorageArea.Config, "../../outside")))
            assertEquals(StorageResult.Value(null), newer.read(StorageRequest(StorageArea.Account, "name")))
            for (other in listOf(scope("b"), scope(profile = "other"))) {
                assertEquals(StorageResult.Value(null), broker.open(other, emptyList()).read(StorageRequest(StorageArea.Config, "../../outside")))
            }
        }
        SourceBroker(directory.root.toPath(), limits = limits).use { broker ->
            assertEquals(StorageResult.Value("config"), broker.open(scope(), emptyList()).read(StorageRequest(StorageArea.Config, "../../outside")))
        }
    }

    @Test fun cacheValuesHaveTtlQuotaDeletionAndRequestVariableWritesAreBounded() = runBlocking {
        SourceBroker(directory.root.toPath(), limits = BrokerLimits(maxCacheBytes = 64)).use { broker ->
            val a = broker.open(scope(), emptyList())
            val b = broker.open(scope("b"), emptyList())
            val item = StorageRequest(StorageArea.Cache, "key", "value", ttlMillis = 20)
            assertEquals(StorageResult.Value("value"), a.write(item))
            assertEquals(StorageResult.Value("value"), a.read(item))
            assertEquals(StorageResult.Value(null), b.read(item))
            delay(30)
            assertEquals(StorageResult.Value(null), a.read(item))
            assertEquals(StorageResult.Failure(FailureCode.StorageQuota), a.write(item.copy(value = "x".repeat(100))))
            a.write(item.copy(ttlMillis = 60000))
            a.write(item.copy(value = null))
            assertEquals(StorageResult.Value(null), a.read(item))
            val variables = RequestVariables(maxBytes = 10, maxEntries = 1)
            variables.put("k", "v")
            val snapshot = variables.snapshot()
            assertEquals(StorageResult.Failure(FailureCode.StorageQuota), variables.put("other", "v"))
            variables.put("k", "new")
            assertEquals("v", snapshot["k"])
        }
    }

    @Test fun postBodyEncodingAndSameOriginRedirectMethodSemanticsAreVerifiedOnTheWire() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                val compiled = RequestCompiler().compile("r", """/submit,{"method":"POST","body":"q={{key}}","charset":"GB2312"}""", server.url("/").toString(), "校园") as CompiledRequest.Ready
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/done"))
                server.enqueue(MockResponse().setBody("ok"))
                success(session.execute(compiled.request.copy(headers = compiled.request.headers + ("Authorization" to "same-origin"))))
                val post = server.recorded()
                assertEquals("POST", post.method)
                assertEquals("q=%D0%A3%D4%B0", post.body.readUtf8())
                val redirected = server.recorded()
                assertEquals("GET", redirected.method)
                assertEquals("same-origin", redirected.getHeader("Authorization"))
                assertEquals(0L, redirected.bodySize)
            }
        }
    }

    @Test fun sameNameCookiesWithDifferentPathsAreBothSentInPathOrder() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse().addHeader("Set-Cookie", "session=root; Path=/")
                    .addHeader("Set-Cookie", "session=admin; Path=/admin"))
                success(session.execute(request(server.url("/login"))))
                server.recorded()
                server.enqueue(MockResponse().setBody("ok"))
                success(session.execute(request(server.url("/admin/page"))))
                assertEquals("session=admin; session=root", server.recorded().getHeader("Cookie"))
            }
        }
    }

    @Test fun cookiesPersistOnlyInTheirAccountAndCannotBeOverwrittenThroughKv() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val a = broker.open(scope(), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse().addHeader("Set-Cookie", "auth=persisted; Max-Age=3600; Path=/"))
                success(a.execute(request(server.url("/login")))); server.recorded()
                a.write(StorageRequest(StorageArea.Account, "cookies", "untrusted"))
            }
            SourceBroker(directory.root.toPath()).use { broker ->
                val a = broker.open(scope(), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse())
                a.execute(request(server.url("/book")))
                assertEquals("auth=persisted", server.recorded().getHeader("Cookie"))
                val newer = broker.open(scope(generation = 1), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse())
                newer.execute(request(server.url("/book")))
                assertNull(server.recorded().getHeader("Cookie"))
            }
        }
    }

    @Test(timeout = 10000) fun cancellationAndTimeoutReleasePermitsAndRetriesAreBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), limits = BrokerLimits(concurrency = 1)).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                server.enqueue(MockResponse().setBody("delayed").setBodyDelay(5, TimeUnit.SECONDS))
                val pending = async { session.execute(request(server.url("/slow"))) }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                pending.cancelAndJoin()
                server.enqueue(MockResponse().setResponseCode(503))
                server.enqueue(MockResponse().setBody("retried"))
                assertEquals("retried", success(withTimeout(3000) { session.execute(request(server.url("/retry")).copy(retry = 1)) }).text())
                server.recorded(); server.recorded()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                assertEquals(FailureCode.Timeout, (session.execute(request(server.url("/timeout")).copy(timeoutMillis = 150)) as BrokerResult.Failure).code)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test(timeout = 10000) fun concurrencyIsPerSourceAndRetirementCancelsOldRequests() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val second = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/slow") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                    if (request.path == "/second") second.incrementAndGet()
                    return MockResponse().setBody("ok")
                }
            }
            server.start()
            SourceBroker(directory.root.toPath(), limits = BrokerLimits(concurrency = 1)).use { broker ->
                val a = broker.open(scope(), listOf(grant(server.url("/"))))
                val b = broker.open(scope("b"), listOf(grant(server.url("/"))))
                val pending = async { a.execute(request(server.url("/slow"))) }
                withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) }
                val queued = async(start = CoroutineStart.UNDISPATCHED) { a.execute(request(server.url("/second"))) }
                try {
                    assertEquals("ok", success(withTimeout(2000) { b.execute(request(server.url("/fast"))) }).text())
                    assertEquals(0, second.get())
                    a.close()
                    withTimeout(2000) { pending.join(); queued.join() }
                    assertTrue(pending.isCancelled); assertTrue(queued.isCancelled)
                    assertThrows(CancellationException::class.java) { runBlocking { a.execute(request(server.url("/"))) } }
                    Unit
                } finally { release.countDown() }
            }
        }
    }

    @Test fun limitsApplyToResponsesHeadersRateAndCacheOnlyNeverFallsBack() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), limits = BrokerLimits(maxResponseBytes = 5, minIntervalMillis = 80)).use { broker ->
                val session = broker.open(scope(), listOf(grant(server.url("/"))))
                assertEquals(BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss), session.execute(request(server.url("/missing"), CacheMode.Only)))
                assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.InvalidRequest), session.execute(request(server.url("/")).copy(headers = mapOf("Host" to "other.invalid"))))
                assertEquals(0, server.requestCount)
                for (kind in ResourceKind.entries) {
                    assertEquals(BrokerResult.Failure(RequestStage.Permission, FailureCode.OriginDenied),
                        session.execute(BrokerRequest("resource", "https://ungranted.invalid/", kind = kind)))
                }
                server.enqueue(MockResponse().setBody("too large"))
                assertEquals(BrokerResult.Failure(RequestStage.Response, FailureCode.ResponseTooLarge), session.execute(request(server.url("/big"))))
                server.enqueue(MockResponse().setBody("ok")); server.enqueue(MockResponse().setBody("ok"))
                val start = System.nanoTime()
                coroutineScope { listOf(async { session.execute(request(server.url("/1"))) }, async { session.execute(request(server.url("/2"))) }).forEach { success(it.await()) } }
                assertTrue((System.nanoTime() - start) / 1_000_000 >= 70)
            }
        }
    }
}
