package hnovel.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A persistent host task owns retries instead of multiplying them with transport retries. */
class RequestRetryContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestRetryContext>

    @Volatile var replaySafe = true
        private set

    fun disallowReplay() { replaySafe = false }
}

/** Only transport facts cross into task recovery; no request URL, headers or body are retained. */
data class RequestRetryHint(val retryAfterMillis: Long = 0)
