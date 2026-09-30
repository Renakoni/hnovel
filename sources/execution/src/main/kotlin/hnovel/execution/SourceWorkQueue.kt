package hnovel.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host scheduling metadata only; priority never grants permission or interaction ownership. */
class SourceWorkRequest(val priority: Int, val startedAtNanos: Long = System.nanoTime()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceWorkRequest>
}

/** Non-preemptive serial queue: higher priority first, FIFO within a priority. */
class SourceWorkQueue {
    private class Waiter(val priority: Int, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private val waiting = mutableListOf<Waiter>()
    private var locked = false

    fun tryLock(): Boolean = synchronized(waiting) {
        if (locked) false else { locked = true; true }
    }

    private suspend fun lock() {
        if (tryLock()) return
        val waiter = Waiter(currentCoroutineContext()[SourceWorkRequest]?.priority ?: 0)
        val admitted = synchronized(waiting) {
            if (!locked) { locked = true; true } else { waiting += waiter; false }
        }
        if (admitted) waiter.ready.complete(Unit)
        try { waiter.ready.await() }
        catch (failure: Throwable) {
            // Cancellation after admission must hand the permit on, not strand the queue.
            if (!synchronized(waiting) { waiting.remove(waiter) }) unlock()
            throw failure
        }
    }

    fun unlock() {
        val next = synchronized(waiting) {
            check(locked)
            waiting.maxByOrNull { it.priority }?.also { waiting.remove(it) }
                .also { if (it == null) locked = false }
        }
        next?.ready?.complete(Unit)
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        lock()
        try { currentCoroutineContext().ensureActive(); return block() }
        finally { unlock() }
    }
}
