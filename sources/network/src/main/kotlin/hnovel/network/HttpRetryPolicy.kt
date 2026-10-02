package hnovel.network

import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlin.random.Random

/** Short, in-session retries only. Longer server cooldowns must remain visible to the task owner. */
internal class HttpRetryPolicy(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitter: (Long) -> Long = { ceiling -> Random.nextLong(ceiling / 2, ceiling + 1) },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun safe(request: BrokerRequest) = request.method in setOf("GET", "HEAD") && request.browser == null

    fun recoverable(failure: IOException): Boolean = generateSequence<Throwable>(failure) { it.cause }
        .take(16).none { it is ProtocolException || it is SSLException ||
            it is InterruptedIOException && it !is SocketTimeoutException }

    suspend fun await(delayMillis: Long) = sleep(delayMillis)

    fun delayMillis(attempt: Int, response: BrokerResponse? = null): Long? {
        if (response != null && response.status !in setOf(429, 502, 503, 504)) return null
        val serverDelay = response?.let { retryAfterMillis(it, nowMillis()) } ?: 0
        // Never clamp a valid server deadline downwards or overflow it into an immediate retry.
        if (serverDelay > MAX_WAIT_MILLIS) return null
        val ceiling = minOf(500L shl attempt.coerceIn(0, 4), MAX_WAIT_MILLIS)
        return maxOf(serverDelay, jitter(ceiling).coerceIn(ceiling / 2, ceiling))
    }

    companion object {
        private const val MAX_WAIT_MILLIS = 5000L
    }
}

/** Invalid values have no server deadline; valid overflowing values must never become zero. */
fun retryAfterMillis(response: BrokerResponse, nowMillis: Long = System.currentTimeMillis()): Long =
    response.headers.entries.filter { it.key.equals("Retry-After", true) }.flatMap { it.value }
        .mapNotNull { retryAfterMillis(it, nowMillis) }.maxOrNull() ?: 0

fun retryAfterMillis(header: String, nowMillis: Long = System.currentTimeMillis()): Long? {
    val value = header.trim()
    if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }
    return try {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        Math.subtractExact(deadline, nowMillis).coerceAtLeast(0)
    } catch (_: DateTimeParseException) {
        null // Malformed headers fall back to bounded local backoff.
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}
