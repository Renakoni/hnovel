package indi.dmzz_yyhyy.lightnovelreader.data.statistics

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes all operations that can change reading statistics rows. */
@Singleton
class StatisticsWriteCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
