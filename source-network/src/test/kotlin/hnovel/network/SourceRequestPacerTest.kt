package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRequestPacerTest {
    @Test fun bareIntervalsAndWindowsHaveExplicitInvalidValueSemantics() {
        assertEquals(SourceRequestRate(1, 2000), SourceRequestRate.parse("2000"))
        assertEquals(SourceRequestRate(2, 2000), SourceRequestRate.parse(" 2 / 2000 "))
        for (value in listOf(null, "", "0", "-1", "0/2000", "-2/2000", "2/0", "2/-1",
            "many/2000", "2/ms", "1.5", "1/2/3", "2147483648/2000", "9223372036854775808"))
            assertNull(value, SourceRequestRate.parse(value))
    }

    @Test fun disabledRateDoesNotWait() = runTest {
        val pacer = SourceRequestPacer(null) { currentTime }
        repeat(20) { pacer.awaitAdmission() }
        assertEquals(0, currentTime)
    }

    @Test fun concurrentWaitersRecheckTheWindow() = runTest {
        val pacer = SourceRequestPacer(SourceRequestRate(2, 2000)) { currentTime }
        val admissions = mutableListOf<Long>()
        repeat(5) { launch { pacer.awaitAdmission(); admissions += currentTime } }
        advanceUntilIdle()
        assertEquals(listOf(0L, 0L, 2000L, 2000L, 4000L), admissions)
    }

    @Test fun idleTimeDoesNotAccumulateCredit() = runTest {
        val pacer = SourceRequestPacer(SourceRequestRate(1, 2000)) { currentTime }
        pacer.awaitAdmission()
        advanceTimeBy(20000)
        pacer.awaitAdmission()
        pacer.awaitAdmission()
        assertEquals(22000, currentTime)
    }

    @Test fun cancelledWaiterDoesNotReserveTheNextWindow() = runTest {
        val pacer = SourceRequestPacer(SourceRequestRate(1, 2000)) { currentTime }
        pacer.awaitAdmission()
        val cancelled = launch { pacer.awaitAdmission(); fail("Cancelled waiter was admitted") }
        runCurrent()
        advanceTimeBy(1000)
        cancelled.cancelAndJoin()
        pacer.awaitAdmission()
        assertEquals(2000, currentTime)
    }

    @Test fun delayedWakeupAnchorsToActualAdmission() = runTest {
        var clock = 0L
        val pacer = SourceRequestPacer(SourceRequestRate(1, 2000)) { clock }
        pacer.awaitAdmission()
        val first = async { pacer.awaitAdmission(); currentTime }
        runCurrent()
        clock = 20000 // Device execution resumes much later than the original deadline.
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2000L, first.await())
        val next = async { pacer.awaitAdmission(); currentTime }
        runCurrent()
        assertFalse(next.isCompleted)
        clock = 22000
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(4000L, next.await())
    }
}
