package indi.renakoni.nextvol.data.work

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Shared across books: at most four documents and two images, leaving native transport headroom. */
internal object NativeDownloadBudget {
    private const val CHAPTERS = 4
    private const val IMAGES = 2
    private val documents = Semaphore(CHAPTERS)
    private val images = Semaphore(IMAGES)

    suspend fun <T> document(enabled: Boolean, block: suspend () -> T): T =
        if (enabled) documents.withPermit { block() } else block()

    suspend fun <T> image(enabled: Boolean, block: suspend () -> T): T =
        if (enabled) images.withPermit { block() } else block()

    fun <T, R> chapters(items: List<T>, enabled: Boolean, load: suspend (T) -> R): Flow<R> {
        if (!enabled) return flow { items.forEach { emit(load(it)) } }
        return channelFlow {
            val next = AtomicInteger()
            List(minOf(CHAPTERS, items.size)) {
                async {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val index = next.getAndIncrement()
                        if (index >= items.size) break
                        // Keep the slot until consumed, bounding both active reads and ready bodies.
                        document(true) { send(load(items[index])) }
                    }
                }
            }.awaitAll()
        }.buffer(0)
    }
}
