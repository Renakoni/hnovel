package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NativeBrowserCookiesTest {
    @Test fun nativeCookieSeedsAdvanceOnlyForHostWritesAndRejectOlderPageHandoffs() = runBlocking {
        val root = Files.createTempDirectory("native-cookie-seed")
        try { SourceBroker(root).use { broker ->
            val url = "https://cookie-seed.test/"
            val session = broker.open(SourceScope("test", "seed", "legado"), listOf(NetworkGrant(url)))
            val initial = session.nativeBrowserCookieSeed(url)
            session.setCookie(url, "account=old")
            val old = session.nativeBrowserCookieSeed(url)
            assertTrue(old.version > initial.version)
            assertTrue(old.cookies.single().startsWith("account=old;"))
            session.updateNativeBrowserCookies(url, listOf("account=browser; Path=/; HttpOnly"), expectedSeedVersion = old.version)
            assertEquals(old.version, session.nativeBrowserCookieSeed(url).version)
            assertTrue(session.nativeBrowserCookieSeed(url).cookies.isEmpty())
            session.setCookie(url, "account=new")
            val newer = session.nativeBrowserCookieSeed(url)
            session.updateNativeBrowserCookies(url, listOf("account=stale; Path=/; HttpOnly"), expectedSeedVersion = old.version)
            assertEquals("account=new", session.cookie(url))
            assertEquals(newer, session.nativeBrowserCookieSeed(url))
            session.removeCookie(url)
            assertTrue(session.nativeBrowserCookieSeed(url).version > newer.version)
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun verifiedCookiesRespectPathsHttpOnlyExpiryAndAccountRetirementWithAutomaticCaptureOff() = runBlocking {
        val root = Files.createTempDirectory("native-cookie-handoff")
        try { MockWebServer().use { server ->
            server.start()
            SourceBroker(root).use { broker ->
                val base = server.url("/").toString()
                val owner = SourceScope("test", "verified", "legado")
                val grants = listOf(NetworkGrant(base, true))
                val session = broker.open(owner, grants).apply { configureSource(base, false) }
                session.updateNativeBrowserCookies(base, listOf("verified=yes; Path=/; HttpOnly", "durable=yes; Path=/; Max-Age=3600"))
                session.updateNativeBrowserCookies(server.url("/private/").toString(), listOf(
                    "verified=yes; Path=/; HttpOnly", "durable=yes; Path=/; Max-Age=3600", "restricted=yes; Path=/private/; HttpOnly"))
                suspend fun cookie(path: String): String {
                    server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "automatic=no; Path=/"))
                    assertTrue(session.execute(BrokerRequest("read", server.url(path).toString())) is BrokerResult.Success)
                    return server.takeRequest().getHeader("Cookie").orEmpty()
                }
                assertTrue(cookie("/chapter").contains("verified=yes"))
                assertFalse(cookie("/chapter").contains("restricted=yes"))
                assertTrue(cookie("/private/chapter").contains("restricted=yes"))
                assertFalse(session.cookie(base).contains("automatic=no"))
                session.configureSource(base, true)
                assertFalse(session.browserCookie(base).contains("verified=yes"))
                assertTrue(session.nativeBrowserCookies(base).isEmpty())
                assertThrows(Exception::class.java) { session.updateNativeBrowserCookies("https://unapproved.invalid/", listOf("secret=yes")) }
                // Legacy header-only handoffs must not change Chromium's original visibility.
                session.updateNativeBrowserCookies(base, listOf("legacy=yes; Path=/; HttpOnly"), completeMetadata = false)
                assertEquals("legacy=yes", session.cookie(base))
                assertTrue(session.nativeBrowserCookies(base).isEmpty())
                session.setCookie(base, "manual=new")
                assertTrue(session.nativeBrowserCookies(base).single().startsWith("manual=new;"))
                // Browser-side deletion removes matching cookies without erasing other paths.
                session.updateNativeBrowserCookies(base, emptyList())
                assertEquals("", session.cookie(base))
                assertEquals("restricted=yes", session.cookie(server.url("/private/").toString()))
                session.updateNativeBrowserCookies(base, listOf("durable=yes; Path=/; Max-Age=3600"))
                session.close()
                val reopened = broker.open(owner, grants)
                assertEquals("durable=yes", reopened.cookie(base))
                assertTrue("Restart must not replay a Chromium cookie without its SameSite attributes", reopened.nativeBrowserCookies(base).isEmpty())
                assertFalse(reopened.cookie(server.url("/private/").toString()).contains("restricted=yes"))
                reopened.clearAccount()
                assertEquals("", broker.open(owner.copy(accountGeneration = 1), grants).cookie(base))
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun secureLoopbackCookieHandoffDoesNotAuthorizePlainHttpOrOtherPaths(): Unit = runBlocking {
        val root = Files.createTempDirectory("secure-loopback-handoff")
        try { SourceBroker(root).use { broker ->
            val base = "http://localhost:18766/"
            val session = broker.open(SourceScope("test", "loopback", "legado"), listOf(NetworkGrant(base, true)))
            session.updateNativeBrowserCookies(base, listOf("secure=value; Secure; HttpOnly; SameSite=None; Path=/"))
            assertEquals("", session.cookie(base))
            assertTrue(session.nativeBrowserCookies(base).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                session.updateNativeBrowserCookies(base, listOf("other=value; Path=/private"))
            }
        } } finally { root.toFile().deleteRecursively() }
    }
}
