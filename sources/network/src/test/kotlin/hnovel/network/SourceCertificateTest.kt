package hnovel.network

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class SourceCertificateTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = SourceScope("rules", "certificate-fixture", "legado")
    private val localDns = okhttp3.Dns { listOf(InetAddress.getByName("127.0.0.1")) }
    private fun broker() = SourceBroker(directory.root.toPath(), dns = localDns)
    private fun certificate(host: String = "localhost") = HeldCertificate.Builder()
        .commonName(host).addSubjectAlternativeName(host)
        .apply { if (host == "localhost") addSubjectAlternativeName("127.0.0.1") }
        .validityInterval(System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 86400000).build()
    private fun server(certificate: HeldCertificate = certificate(), port: Int = 0) = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), port)
    }
    private fun url(server: MockWebServer, path: String = "/") = server.url(path).newBuilder().host("localhost").build()
    private fun grant(server: MockWebServer) = NetworkGrant(url(server).toString(), allowPrivateAddresses = true)
    private fun request(server: MockWebServer, cache: CacheMode = CacheMode.Disabled) =
        BrokerRequest("certificate", url(server, "/chapter?private=fixture").toString(), cache = cache)
    private fun problem(result: BrokerResult): CertificateProblem {
        val failure = result as BrokerResult.Failure
        assertEquals(FailureCode.Certificate, failure.code)
        assertNotNull(failure.certificate)
        return failure.certificate!!
    }

    @Test fun validCertificatesKeepTheSystemTrustManagersHandshakeContext() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().trustManager
        var socketVerified = false
        // Android's RootTrustManager rejects the two-argument overload when it needs a hostname.
        val contextual = object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers() = trust.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = trust.checkClientTrusted(chain, authType)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = checkClientTrusted(chain, authType)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException("Handshake hostname required")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
                assertTrue(socket is javax.net.ssl.SSLSocket)
                trust.checkServerTrusted(chain, authType)
                socketVerified = true
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = trust.checkServerTrusted(chain, authType)
        }
        server(certificate).use { server ->
            val certificates = SourceCertificates(SourceStorage(directory.root.toPath(), scope.components(true), BrokerLimits()), contextual)
            val client = certificates.configure(okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(localDns),
                sourceOrigin(url(server).toString())!!).build()
            try {
                server.enqueue(MockResponse().setBody("system trusted"))
                client.newCall(okhttp3.Request.Builder().url(url(server)).build()).execute().use {
                    assertEquals("system trusted", it.body.string())
                }
                assertTrue(socketVerified)
                assertTrue(certificates.exceptions().isEmpty())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun handshakeRetainsTheCertificateFailureCause() {
        server().use { server ->
            val certificates = SourceCertificates(SourceStorage(directory.root.toPath(), scope.components(true), BrokerLimits()))
            val client = certificates.configure(okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(localDns),
                sourceOrigin(url(server).toString())!!).build()
            try {
                val failed = runCatching { client.newCall(okhttp3.Request.Builder().url(url(server, "/test")).build()).execute().close() }
                    .exceptionOrNull()
                val causes = generateSequence(failed) { it.cause }.take(16).toList()
                assertTrue(causes.joinToString("\n"), causes.any { it is RejectedCertificate })
                val problem = causes.filterIsInstance<RejectedCertificate>().single().problem
                certificates.remember(problem)
                certificates.approve(problem)
                val accepted = certificates.configure(client.newBuilder(), problem.origin).build()
                server.enqueue(MockResponse().setBody("confirmed"))
                try {
                    accepted.newCall(okhttp3.Request.Builder().url(url(server, "/test")).build()).execute().use {
                        assertEquals("confirmed", it.body.string())
                    }
                } catch (failure: Exception) {
                    throw AssertionError("Confirmed certificate at ${problem.origin}: ${failure.message}", failure)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun expiredCertificateRequiresConsentAndPersistsOnlyForTheExactAccount() = runBlocking {
        server().use { server -> broker().use { broker ->
            val request = request(server).copy(headers = mapOf("Authorization" to "fixture-secret"))
            val grants = listOf(grant(server))
            val session = broker.open(scope, grants)
            val rejected = problem(session.execute(request))
            assertEquals(CertificateIssue.Expired, rejected.issue)
            assertEquals(sourceOrigin(request.url), rejected.origin)
            assertFalse(rejected.toString().contains("private"))
            assertEquals(0, server.requestCount)
            // Scripts cannot create an exception through their ordinary storage API.
            session.write(StorageRequest(StorageArea.Account, "sites", "fixture"))
            assertTrue(session.certificateExceptions().isEmpty())
            session.approveCertificate(rejected)
            assertEquals(rejected.fingerprint, session.certificateExceptions().single().fingerprint)
            server.enqueue(MockResponse().setBody("chapter"))
            assertEquals("chapter", (session.execute(request) as BrokerResult.Success).response.text())
            assertEquals("fixture-secret", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            session.close()
            val restored = broker.open(scope, grants)
            server.enqueue(MockResponse().setBody("restored"))
            assertEquals("restored", (restored.execute(request) as BrokerResult.Success).response.text())
            for (other in listOf(scope.copy(sourceId = "other"), scope.copy(profile = "other"), scope.copy(accountGeneration = 1))) {
                val isolated = broker.open(other, grants)
                assertTrue(isolated.certificateExceptions().isEmpty())
                problem(isolated.execute(request))
            }
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun redirectToAnotherOriginDoesNotInheritTheExceptionEvenForTheSameCertificate() = runBlocking {
        val shared = certificate()
        server(shared).use { first -> server(shared).use { second -> broker().use { broker ->
            val session = broker.open(scope, listOf(grant(first), grant(second)))
            val firstProblem = problem(session.execute(request(first)))
            session.approveCertificate(firstProblem)
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url(second, "/target")))
            val secondProblem = problem(session.execute(request(first)))
            assertEquals(sourceOrigin(url(second).toString()), secondProblem.origin)
            assertEquals(firstProblem.fingerprint, secondProblem.fingerprint)
            assertEquals(0, second.requestCount)
        } } }
    }

    @Test fun changedCertificateAtTheSameOriginNeedsFreshConsent() = runBlocking {
        val first = server()
        val port = first.port
        val grants = listOf(grant(first))
        broker().use { broker ->
            val session = broker.open(scope, grants)
            val previous = problem(session.execute(request(first)))
            session.approveCertificate(previous)
            session.close()
            first.close()
            server(port = port).use { second ->
                val restored = broker.open(scope, grants)
                val changed = problem(restored.execute(request(second)))
                assertEquals(previous.origin, changed.origin)
                assertNotEquals(previous.fingerprint, changed.fingerprint)
                assertTrue(runCatching { restored.approveCertificate(previous) }.isFailure)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test fun revocationCancelsAnActiveBodyAndDoesNotReuseItsConnectionOrCache() = runBlocking {
        server().use { server -> broker().use { broker ->
            val grants = listOf(grant(server))
            val session = broker.open(scope, grants)
            val rejected = problem(session.execute(request(server)))
            session.approveCertificate(rejected)
            server.enqueue(MockResponse().setBody("cached"))
            assertTrue(session.execute(request(server, CacheMode.ReadThrough)) is BrokerResult.Success)
            server.takeRequest(1, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody("late body").setBodyDelay(2, TimeUnit.SECONDS)
                .addHeader("Set-Cookie", "late=credential; Path=/"))
            val active = async(Dispatchers.IO) { session.execute(request(server)) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            session.revokeCertificate(rejected.origin)
            assertTrue(session.closed)
            assertTrue(runCatching { active.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { session.approveCertificate(rejected) }.isFailure)
            val fresh = broker.open(scope, grants)
            assertTrue(fresh.certificateExceptions().isEmpty())
            assertEquals(FailureCode.CacheMiss, (fresh.execute(request(server, CacheMode.Only)) as BrokerResult.Failure).code)
            assertFalse(fresh.cookie(request(server).url).contains("late="))
            problem(fresh.execute(request(server)))
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun consentCannotOverrideHostnameValidationAndCannotBeFabricated() = runBlocking {
        server(certificate("another.example")).use { server -> broker().use { broker ->
            val session = broker.open(scope, listOf(grant(server)))
            val forged = CertificateProblem.from(sourceOrigin(url(server).toString())!!,
                certificate().certificate, CertificateIssue.Expired)
            assertTrue(runCatching { session.approveCertificate(forged) }.isFailure)
            val rejected = problem(session.execute(request(server)))
            session.approveCertificate(rejected)
            val failure = session.execute(request(server)) as BrokerResult.Failure
            assertEquals(FailureCode.Certificate, failure.code)
            assertNull(failure.certificate)
            assertEquals(0, server.requestCount)
        } }
    }
}
