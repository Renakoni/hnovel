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

/** Non-preemptive admission: higher priority first, FIFO within a priority. */
class SourceWorkQueue(private val permits: Int = 1, private val maxPriorityBypasses: Int = Int.MAX_VALUE) {
    init { require(permits > 0); require(maxPriorityBypasses > 0) }
    private class Waiter(val priority: Int, val ready: CompletableDeferred<Unit> = CompletableDeferred(), var bypasses: Int = 0)
    private val waiting = mutableListOf<Waiter>()
    private var active = 0

    fun tryLock(): Boolean = synchronized(waiting) {
        if (active == permits) false else { active++; true }
    }

    private suspend fun lock() {
        if (tryLock()) return
        val waiter = Waiter(currentCoroutineContext()[SourceWorkRequest]?.priority ?: 0)
        val admitted = synchronized(waiting) {
            if (active < permits) { active++; true } else { waiting += waiter; false }
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
            check(active > 0)
            val admitted = waiting.firstOrNull { it.bypasses >= maxPriorityBypasses }
                ?: waiting.maxByOrNull { it.priority }
            if (admitted == null) active-- else {
                // Bound starvation without preempting a running task. Only older
                // waiters passed by this admission consume their bypass budget.
                for (waiter in waiting) {
                    if (waiter === admitted) break
                    if (waiter.bypasses < maxPriorityBypasses) waiter.bypasses++
                }
                waiting.remove(admitted)
            }
            admitted
        }
        next?.ready?.complete(Unit)
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        lock()
        try { currentCoroutineContext().ensureActive(); return block() }
        finally { unlock() }
    }
}
