package hnovel.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Legado: bare M means one admission per M milliseconds; N/M is a fixed window. */
internal data class SourceRequestRate(val count: Int, val intervalMillis: Long) {
    companion object {
        fun parse(value: String?): SourceRequestRate? {
            val parts = value?.trim()?.split('/') ?: return null
            if (parts.size !in 1..2) return null
            val count = if (parts.size == 1) 1 else parts[0].trim().toIntOrNull() ?: return null
            val interval = parts.last().trim().toLongOrNull() ?: return null
            return if (count > 0 && interval > 0) SourceRequestRate(count, interval) else null
        }
    }
}

/** Records actual admissions, never future reservations or accumulated idle-window credit. */
internal class SourceRequestPacer(val rate: SourceRequestRate?,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private var started = 0L
    private var admitted = 0

    suspend fun awaitAdmission() {
        val limit = rate ?: return
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = mutex.withLock {
                val now = nowMillis()
                val elapsed = now - started
                when {
                    admitted == 0 || elapsed >= limit.intervalMillis -> {
                        started = now
                        admitted = 1
                        0L
                    }
                    admitted < limit.count -> { admitted++; 0L }
                    else -> limit.intervalMillis - elapsed
                }
            }
            if (wait == 0L) return
            delay(wait)
        }
    }
}
