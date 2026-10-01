package indi.renakoni.nextvol.coroutine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class PriorityDispatcherTest {
    // Commands before this barrier have reached the coordinator, including requests
    // waiting for admission. The barrier itself does not own a request permit.
    private suspend fun PriorityDispatcher.queued() {
        val reached = CompletableDeferred<Unit>()
        dispatch(EmptyCoroutineContext, Runnable { reached.complete(Unit) })
        withTimeout(5000) { reached.await() }
    }

    @Test fun cancellingQueuedRequestFinishesWithoutReleasingTheRunningRequest() = runBlocking {
        val dispatcher = PriorityDispatcher(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val holder = launch {
            withContext(dispatcher) { entered.complete(Unit); release.await() }
        }
        withTimeout(5000) { entered.await() }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(dispatcher + PriorityDispatcher.Priority(10)) { order += "cancelled" }
        }
        val low = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(dispatcher + PriorityDispatcher.Priority(0)) { order += "low" }
        }
        val high = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(dispatcher + PriorityDispatcher.Priority(5)) { order += "high" }
        }
        try {
            dispatcher.queued()
            cancelled.cancel()
            assertTrue("Cancellation must finish while the running request still holds its permit",
                withTimeoutOrNull(1000) { cancelled.join(); true } == true)
            dispatcher.queued()
            assertTrue(holder.isActive)
            assertTrue(order.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { joinAll(holder, low, high) }
            assertEquals(listOf("high", "low"), order)
        } finally {
            release.complete(Unit)
            withTimeout(5000) { joinAll(holder, cancelled, low, high); dispatcher.close() }
        }
    }

    @Test fun callerDeadlineCanExpireInTheQueueWithoutExecutingTheRequest() = runBlocking {
        val dispatcher = PriorityDispatcher(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requestedBodyRan = false
        val holder = launch {
            withContext(dispatcher) { entered.complete(Unit); release.await() }
        }
        withTimeout(5000) { entered.await() }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(200) {
                withContext(dispatcher) { requestedBodyRan = true }
                "completed"
            }
        }
        try {
            dispatcher.queued()
            assertTrue("The caller's deadline must not wait for an unrelated request",
                withTimeoutOrNull(2000) { pending.join(); true } == true)
            assertNull(pending.await())
            assertFalse(requestedBodyRan)
            assertTrue(holder.isActive)
        } finally {
            release.complete(Unit)
            withTimeout(5000) { joinAll(holder, pending); dispatcher.close() }
        }
    }
}
