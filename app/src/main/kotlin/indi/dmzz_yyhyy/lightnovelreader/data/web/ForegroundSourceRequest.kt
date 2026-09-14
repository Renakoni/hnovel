package indi.dmzz_yyhyy.lightnovelreader.data.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owned by a UI entry. Neither a source script nor request priority grants interaction. */
class ForegroundSourceRequest(val allowsInteraction: Boolean = true) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ForegroundSourceRequest>
    private val verifications = java.util.concurrent.atomic.AtomicInteger()
    val verifying get() = verifications.get() > 0
    internal fun beginVerification() { verifications.incrementAndGet() }
    internal fun endVerification() { verifications.decrementAndGet() }
    private val active = MutableStateFlow(true)
    private val waiting = mutableSetOf<Job>()
    fun setActive(value: Boolean, retainBrowser: Boolean = false) = synchronized(waiting) {
        active.value = value
        if (!value && (!retainBrowser || !verifying)) waiting.toList().forEach { it.cancel() }
    }
    internal suspend fun <T> ownVerification(block: suspend () -> T): T = coroutineScope {
        val request = currentCoroutineContext().job
        synchronized(waiting) {
            if (!active.value) throw CancellationException("Source UI is inactive")
            waiting += request
        }
        try { block() } finally { synchronized(waiting) { waiting -= request } }
    }
    suspend fun awaitActive() { active.first { it } }
}
