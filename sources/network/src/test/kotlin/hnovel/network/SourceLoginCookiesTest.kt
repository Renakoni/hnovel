package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SourceLoginCookiesTest {
    @Test fun completedForegroundBrowserPersistsSessionStatusWithTheAccount() = runBlocking {
        val root = Files.createTempDirectory("browser-session-status")
        val scope = SourceScope("test", "login", "legado")
        val url = "https://example.org/"
        val grants = listOf(NetworkGrant(url))
        val browser = BrowserExecutor { session, request, _, guard, _ ->
            guard.commit { session.setCookie(request.url, "sid=saved") }
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), byteArrayOf(),
                "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
        try {
            SourceBroker(root, browser = browser).use { broker ->
                val session = broker.open(scope, grants)
                assertTrue(session.execute(BrokerRequest("login", url, browser = BrowserOptions(interactive = true))) is BrokerResult.Success)
            }
            SourceBroker(root).use { broker ->
                val restored = broker.open(scope, grants)
                assertEquals(StorageResult.Value("session"), restored.read(StorageRequest(StorageArea.Account, "login/status")))
                assertEquals("sid=saved", restored.cookie(url))
                assertEquals(StorageResult.Value(null), broker.open(scope.copy(accountGeneration = 1), grants)
                    .read(StorageRequest(StorageArea.Account, "login/status")))
                restored.clearAccount()
                assertEquals(StorageResult.Value(null), broker.open(scope, grants)
                    .read(StorageRequest(StorageArea.Account, "login/status")))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun backgroundFailedOversizedAndCancelledBrowsersDoNotSaveSessionStatus() = runBlocking {
        val root = Files.createTempDirectory("browser-session-failure")
        val url = "https://example.org/"
        val document = BrokerResult.Success(BrokerResponse(0, url, emptyMap(), byteArrayOf(1, 2),
            "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        var response: BrokerResult = document
        val browser = BrowserExecutor { _, _, _, _, _ -> response }
        val status = StorageRequest(StorageArea.Account, "login/status")
        try { SourceBroker(root, browser = browser).use { broker ->
            val session = broker.open(SourceScope("test", "login", "legado"), listOf(NetworkGrant(url)))
            session.write(status.copy(value = "required"))
            val request = BrokerRequest("login", url, browser = BrowserOptions(interactive = true))
            assertTrue(session.execute(request.copy(browser = BrowserOptions())) is BrokerResult.Success)
            assertEquals(StorageResult.Value("required"), session.read(status))
            response = BrokerResult.Failure(RequestStage.Connect, FailureCode.Network)
            assertTrue(session.execute(request) is BrokerResult.Failure)
            assertEquals(StorageResult.Value("required"), session.read(status))
            response = BrokerResult.Success(BrokerResponse(403, url, emptyMap(), byteArrayOf(), "UTF-8", 0))
            session.execute(request)
            assertEquals(StorageResult.Value("required"), session.read(status))
            response = document
            assertEquals(FailureCode.ResponseTooLarge, (session.execute(request.copy(maxResponseBytes = 1)) as BrokerResult.Failure).code)
            assertEquals(StorageResult.Value("required"), session.read(status))
            try {
                session.execute(request, RequestCommitGuard { throw kotlinx.coroutines.CancellationException("Retired account") })
                fail("A retired request must not commit")
            } catch (_: kotlinx.coroutines.CancellationException) { /* Expected cancellation. */ }
            assertEquals(StorageResult.Value("required"), session.read(status))
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failedAccountFileCleanupStillAttemptsCookieAndBrowserCleanup() {
        val root = Files.createTempDirectory("account-cleanup-failure")
        val scope = SourceScope("test", "cleanup", "legado")
        var browserCleared = false
        val browser = object : BrowserExecutor {
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("No navigation")
            override fun clearAccount(scope: SourceScope, localStorage: LocalStorageRetention) { browserCleared = true }
        }
        try { SourceBroker(root, browser = browser).use { broker ->
            val session = broker.open(scope, listOf(NetworkGrant("https://example.org")))
            session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, "old"))
            session.setCookie("https://example.org", "sid=old")
            val directory = root.resolve(hash((scope.components(true) + "account").joinToString("") { "${it.length}:$it" }))
            Files.createDirectory(directory.resolve("broken-entry"))
            assertThrows(IllegalStateException::class.java) { session.clearAccount() }
            assertTrue(session.closed)
            assertTrue("Browser cleanup must still run after account file failure", browserCleared)
            assertEquals(StorageResult.Value(null), SourceStorage(root, scope.components(true) + "cookies", BrokerLimits()).read("cookies"))
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun disabledAutomaticJarStillAllowsExplicitCookieApiAndAccountCleanup() = runBlocking {
        val root = Files.createTempDirectory("login-cookies")
        try { SourceBroker(root).use { broker -> MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            val session = broker.open(SourceScope("test", "one", "legado"), listOf(NetworkGrant(base, true)))
            session.configureSource(base, false)
            session.setCookie(base, "manual=yes")
            server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "auto=no; Path=/"))
            session.execute(BrokerRequest("one", base))
            assertEquals("manual=yes", server.takeRequest().getHeader("Cookie"))
            assertEquals("manual=yes", session.cookie(base))
            server.enqueue(MockResponse().setBody("ok"))
            session.execute(BrokerRequest("two", base, headers = mapOf("Cookie" to session.cookie(base))))
            assertEquals("manual=yes", server.takeRequest().getHeader("Cookie"))
            session.removeCookie(base)
            assertEquals("", session.cookie(base))
            session.setCookie(base, "manual=old")
            session.clearAccount()
            val fresh = broker.open(SourceScope("test", "one", "legado", 1), listOf(NetworkGrant(base, true)))
            assertEquals("", fresh.cookie(base))
        } } } finally { root.toFile().deleteRecursively() }
    }
}
