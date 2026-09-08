package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlipPaginationCoordinatorTest {
    @Test
    fun newerRequestCancelsAnOlderRequestBeforeItCanPublish() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val coordinator = FlipPaginationCoordinator(
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            paginate = { _, _, width ->
                if (width == 1) gate.await()
                emptyList()
            },
        )

        coordinator.submit(emptyList(), height = 10, width = 1) { events += "old" }
        runCurrent()
        coordinator.submit(emptyList(), height = 10, width = 2) { events += "new" }
        advanceUntilIdle()

        assertEquals(listOf("new"), events)
    }

    @Test
    fun closePreventsACompletedRequestFromPublishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<List<AbstractContentComponent<*>>>()
        val coordinator = FlipPaginationCoordinator(
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            paginate = { _, _, _ ->
                gate.await()
                emptyList()
            },
        )

        coordinator.submit(emptyList(), height = 10, width = 1) { events += it }
        runCurrent()
        coordinator.close()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<List<AbstractContentComponent<*>>>(), events)
    }

    @Test
    fun cancelPendingPreventsACompletedRequestFromPublishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val coordinator = FlipPaginationCoordinator(
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            paginate = { _, _, _ ->
                gate.await()
                emptyList()
            },
        )

        coordinator.submit(emptyList(), height = 10, width = 1) { events += "stale" }
        runCurrent()
        coordinator.cancelPending()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), events)
    }
}
