package indi.renakoni.nextvol.data.image

import io.nightfish.lightnovelreader.api.error.WebRequestError
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SourceImageErrorTest {
    private fun wrapped(error: Throwable) = SourceImageRequestException(WebRequestError("fixture", "private", error))

    @Test fun httpRejectionsRetainStatusWithoutBecomingTransportFailures() {
        for (status in listOf(401, 403, 404)) {
            val error = wrapped(SourceImageHttpException(status))
            assertEquals(status, error.httpStatus)
            assertFalse(error.networkFailure)
            assertNull(error.retry)
            assertFalse(error.message.orEmpty().contains("private"))
        }
        assertTrue(wrapped(IOException("disconnected")).networkFailure)
    }

    @Test fun retryableHttpStatusesKeepTheServerDeadlineAndOverflowCannotBecomeZero() {
        for (status in listOf(429, 502, 503, 504)) {
            val error = wrapped(SourceImageHttpException(status, listOf("1", "3")))
            assertEquals(status, error.httpStatus)
            assertEquals(3000L, error.retry!!.retryAfterMillis)
            assertFalse(error.networkFailure)
        }
        assertEquals(Long.MAX_VALUE, wrapped(SourceImageHttpException(429, listOf("999999999999999999999999"))).retry!!.retryAfterMillis)
    }

    @Test fun downloadClassificationDistinguishesRejectionRateLimitAndTransportFailure() {
        fun category(error: Throwable) = indi.renakoni.nextvol.data.download.downloadFailure(
            WebRequestError("fixture", "private", wrapped(error)), indi.renakoni.nextvol.data.download.DownloadStage.Body)
        assertEquals(indi.renakoni.nextvol.data.download.DownloadFailure.SourceRequest, category(SourceImageHttpException(403)))
        assertEquals(indi.renakoni.nextvol.data.download.DownloadFailure.RateLimited, category(SourceImageHttpException(429)))
        assertEquals(indi.renakoni.nextvol.data.download.DownloadFailure.Network, category(IOException("disconnected")))
    }
}
