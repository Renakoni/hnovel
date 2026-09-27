package indi.renakoni.nextvol.sourceexecution

import org.junit.Assert.assertEquals
import org.junit.Test

class IsolatedWorkerCapacityTest {
    @Test fun lowRamDevicesKeepOneIndependentWorkerEvenWithManyCores() {
        for (cores in listOf(1, 2, 4, 8, 16)) assertEquals(1, independentWorkerParallelism(cores, true))
    }

    @Test fun ordinaryDevicesUseAvailableCoresWithinTheFourProcessBudget() {
        for ((cores, expected) in listOf(0 to 1, 1 to 1, 2 to 2, 4 to 4, 8 to 4, 16 to 4))
            assertEquals(expected, independentWorkerParallelism(cores, false))
    }
}
