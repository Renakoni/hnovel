package indi.renakoni.nextvol.data.download

import hnovel.network.RequestRetryHint
import kotlin.random.Random

/** Three recovery attempts per task, shared by details, pagination, chapters and images. */
internal class DownloadRetryPolicy(
    val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitter: (Long) -> Long = { Random.nextLong(it / 2, it + 1) },
) {
    fun nextAttemptAt(retries: Int, hint: RequestRetryHint): Long? {
        if (retries !in 0 until MAX_RETRIES || hint.retryAfterMillis > MAX_SERVER_WAIT) return null
        val ceiling = 30_000L shl retries
        val wait = maxOf(hint.retryAfterMillis, jitter(ceiling).coerceIn(ceiling / 2, ceiling))
        return Math.addExact(nowMillis(), wait)
    }

    companion object {
        const val MAX_RETRIES = 3
        // A longer server deadline requires a later explicit user decision, not an early automatic retry.
        const val MAX_SERVER_WAIT = 24 * 60 * 60 * 1000L
    }
}
