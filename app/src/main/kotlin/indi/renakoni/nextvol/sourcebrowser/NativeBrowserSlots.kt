package indi.renakoni.nextvol.sourcebrowser

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** Admission is ordered before taking permits, so two exclusive owners cannot each hold half. */
internal class NativeBrowserSlots(private val capacity: Int) {
    private val admission = Mutex()
    private val permits = Semaphore(capacity)

    inner class Lease internal constructor(private var count: Int) : AutoCloseable {
        fun single() { while (count > 1) { count--; permits.release() } }
        override fun close() { while (count > 0) { count--; permits.release() } }
    }

    suspend fun acquire(shared: () -> Boolean = { false }, prepare: suspend (Boolean) -> Unit = {}): Lease = admission.withLock {
        var count = 0
        suspend fun take(size: Int) { repeat(size) { permits.acquire(); count++ } }
        try {
            take(if (shared()) 1 else capacity)
            // The connection may retire while this request waits for an existing page.
            if (count == 1 && !shared()) { permits.release(); count = 0; take(capacity) }
            prepare(count == capacity)
            Lease(count)
        } catch (failure: Throwable) {
            repeat(count) { permits.release() }
            throw failure
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val lease = acquire()
        return try { block() } finally { lease.close() }
    }
}
