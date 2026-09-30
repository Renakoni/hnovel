package hnovel.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

class HttpRetryPolicyTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
    private val policy = HttpRetryPolicy({ now }, { it })
    private fun response(header: String, status: Int = 503) = BrokerResponse(status, "https://example.test/",
        mapOf("retry-after" to listOf(header)), byteArrayOf(), "UTF-8", 0)

    @Test fun backoffIsBoundedAndJitterNeverRemovesTheMinimumWait() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 5000L, 5000L), (0..5).map { policy.delayMillis(it) })
        val minimum = HttpRetryPolicy({ now }, { 0 })
        assertEquals(250L, minimum.delayMillis(0))
        assertEquals(2500L, minimum.delayMillis(100))
    }

    @Test fun serverSecondsAndDatesAreLowerBounds() {
        assertEquals(2000L, policy.delayMillis(0, response("2")))
        assertEquals(3000L, policy.delayMillis(0, response("Mon, 28 Sep 2026 00:00:03 GMT")))
        assertEquals(500L, policy.delayMillis(0, response("Sun, 27 Sep 2026 00:00:00 GMT")))
        assertEquals(500L, policy.delayMillis(0, response("0")))
        assertEquals(5000L, policy.delayMillis(0, response("5")))
    }

    @Test fun malformedHeadersBackOffButLongOrOverflowingDeadlinesDoNotRetryEarly() {
        for (header in listOf("", "later", "-1", "1.5")) assertEquals(header, 500L, policy.delayMillis(0, response(header)))
        for (header in listOf("6", Long.MAX_VALUE.toString(), "9999999999999999999999999",
            "Tue, 29 Sep 2026 00:00:00 GMT")) assertNull(header, policy.delayMillis(0, response(header)))
    }

    @Test fun duplicateHeadersCannotShortenACooldown() {
        val repeated = response("0").copy(headers = mapOf("Retry-After" to listOf("0", "3", "invalid")))
        assertEquals(3000L, policy.delayMillis(0, repeated))
        assertNull(policy.delayMillis(0, repeated.copy(headers = mapOf("Retry-After" to listOf("0", "60")))))
    }

    @Test fun onlySafeHttpReadsAndTransientFailuresQualify() {
        for (method in listOf("GET", "HEAD")) assertTrue(policy.safe(BrokerRequest("r", "https://example.test", method)))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) assertFalse(policy.safe(BrokerRequest("r", "https://example.test", method)))
        assertFalse(policy.safe(BrokerRequest("r", "https://example.test", browser = BrowserOptions())))
        for (failure in listOf(IOException(), UnknownHostException(), SocketTimeoutException())) assertTrue(policy.recoverable(failure))
        for (failure in listOf(ProtocolException(), InterruptedIOException(), SSLHandshakeException("tls"),
            IOException(SSLHandshakeException("tls")))) assertFalse(policy.recoverable(failure))
        for (status in listOf(200, 401, 403, 404, 408, 500)) assertNull(policy.delayMillis(0, response("0", status)))
    }
}
