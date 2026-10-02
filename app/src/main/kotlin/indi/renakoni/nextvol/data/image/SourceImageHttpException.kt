package indi.renakoni.nextvol.data.image

import hnovel.network.RequestRetryHint
import hnovel.network.retryAfterMillis
import java.io.IOException

/** An HTTP rejection is not a transport failure. Keep status/deadline, never the private response body. */
internal class SourceImageHttpException(val httpStatus: Int, retryAfter: List<String> = emptyList()) :
    IOException("Image request failed ($httpStatus)") {
    val retry = if (httpStatus in setOf(429, 502, 503, 504))
        RequestRetryHint(retryAfter.mapNotNull { retryAfterMillis(it) }.maxOrNull() ?: 0) else null
}
