package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BangumiApiTest {
    private val server = MockWebServer()
    private lateinit var api: BangumiApi
    private val session = BangumiSession(BangumiUser(17, "test"), "generation", "test-only-token")
    @Before fun setup() { server.start(); api = BangumiApi(OkHttpClient(), server.url("/")) }
    @After fun close() { session.revoke(); server.shutdown() }

    @Test fun progressRequestOnlyContainsVolumes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.updateVolumes(session, 123, 9)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PATCH", request.method)
        assertEquals("/v0/users/-/collections/123", request.path)
        assertEquals("{\"vol_status\":9}", request.body.readUtf8())
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("User-Agent")!!.contains("Renakoni/NextVol/"))
    }

    @Test fun statusChangesAndCreationAreSeparateFromProgressAndAcceptEmptySuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(204))
        api.createCollection(session, 123, true)
        api.resumeCollection(session, 123)
        assertEquals("{\"type\":3,\"private\":true}", server.takeRequest().body.readUtf8())
        assertEquals("{\"type\":3}", server.takeRequest().body.readUtf8())
    }

    @Test fun errorsRetainStatusAndBackoffButNeverTheBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("sensitive upstream response"))
        try { api.updateVolumes(session, 123, 1); fail() }
        catch (failure: BangumiApiException) {
            assertEquals(429, failure.status)
            assertEquals(60L, failure.retryAfterSeconds)
            assertFalse(failure.toString().contains("sensitive"))
        }
    }

    @Test fun missingCollectionIsDistinctFromUnauthorized() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(api.collection(session, 123))
        try { api.collection(session, 123); fail() } catch (failure: BangumiApiException) { assertEquals(401, failure.status) }
    }

    @Test fun onlyOfficialSubjectLinksSupplyIds() {
        assertEquals(123, BangumiApi.subjectId("http://bangumi.tv/subject/123"))
        assertEquals(123, BangumiApi.subjectId("https://bgm.tv/subject/123"))
        assertNull(BangumiApi.subjectId("https://example.com/subject/123"))
        assertNull(BangumiApi.subjectId("https://bgm.tv@evil.example/subject/123"))
        assertNull(BangumiApi.subjectId("-1"))
    }

    @Test fun redirectsCannotForwardTheAccountCredential() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        try { api.collection(session, 123); fail() }
        catch (failure: BangumiApiException) { assertEquals(302, failure.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedResponsesNeverEscapeThroughExceptionMessages() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"private-account-data\":"))
        try { api.collection(session, 123); fail() }
        catch (failure: BangumiResponseException) {
            assertEquals("Invalid Bangumi response", failure.message)
            assertNull(failure.cause)
        }
    }
}
