package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SourceLoginCookiesTest {
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
            assertNull(server.takeRequest().getHeader("Cookie"))
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
