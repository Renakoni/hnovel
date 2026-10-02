package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultUserAgentTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun transportFillsMissingOrBlankUaWithoutChangingExplicitHeaders() {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().addInterceptor(DefaultUserAgentInterceptor("source-default")).build()
            try {
                for (ua in listOf(null, "", "   ", "source-explicit")) {
                    server.enqueue(MockResponse())
                    val request = Request.Builder().url(server.url("/cover")).header("Cookie", "explicit=kept")
                    ua?.let { request.header("uSeR-aGeNt", it) }
                    client.newCall(request.build()).execute().close()
                    val received = server.takeRequest()
                    assertEquals(if (ua.isNullOrBlank()) "source-default" else ua, received.getHeader("User-Agent"))
                    assertEquals("explicit=kept", received.getHeader("Cookie"))
                    assertEquals(1, received.headers.values("User-Agent").size)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun ruleRequestsRetainTheLastNonblankIdentityAndResolveTheActualTransportValue() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("test", "ua", "legado"), listOf(NetworkGrant(base, true)))
                for (default in listOf(null, "session-identity")) {
                    session.configureSource(base, true, defaultUserAgent = default)
                    for (ua in listOf(null, "", " ", "request-identity")) {
                        val headers = ua?.let { mapOf("user-agent" to it) }.orEmpty()
                        val expected = ua?.takeIf { it.isNotBlank() } ?: default ?: DESKTOP_USER_AGENT
                        server.enqueue(MockResponse())
                        val response = session.execute(BrokerRequest("image", base, kind = ResourceKind.Image, headers = headers))
                        assertTrue(response.toString(), response is BrokerResult.Success)
                        assertEquals(expected, server.takeRequest().getHeader("User-Agent"))
                        assertEquals(expected, session.requestUserAgent(base, headers))
                    }
                }
            }
        }
    }
}
