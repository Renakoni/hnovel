package indi.renakoni.nextvol.data.web

import android.app.Application
import com.github.michaelbull.result.coroutines.coroutineBinding
import indi.renakoni.nextvol.data.web.proxy.ProxyPriorityWebBookDataSource
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ProxyQueueBoundaryTest {
    @Test fun nestedSourceOperationsDoNotCompeteWithTheirOwnRequestsForAdmission() = runBlocking {
        val bodies = AtomicInteger()
        val directories = AtomicInteger()
        val allBodies = CompletableDeferred<Unit>()
        val allDirectories = CompletableDeferred<Unit>()
        val source = object : WebBookDataSource by EmptyWebDataSource {
            override val permits = 8
            override suspend fun getBookVolumes(id: String) = coroutineBinding<BookVolumes, WebRequestError> {
                withContext(Dispatchers.IO) {
                    if (directories.incrementAndGet() == 4) allDirectories.complete(Unit)
                    allDirectories.await()
                }
                BookVolumes(id, emptyList())
            }
            override suspend fun getChapterContent(chapterId: String, bookId: String) = coroutineBinding<ChapterContent, WebRequestError> {
                withContext(Dispatchers.IO) {
                    if (bodies.incrementAndGet() == 4) allBodies.complete(Unit)
                    allBodies.await()
                }
                getBookVolumes(bookId).bind()
                ChapterContent(chapterId, bookId, buildJsonObject {})
            }
        }
        val proxy = ProxyPriorityWebBookDataSource(source)
        val requests = List(4) { async { proxy.getChapterContent("$it", "book", WebDataSourcePriority.Low) } }
        try {
            val results = withTimeoutOrNull(3000) { requests.awaitAll() }
            assertNotNull("Nested work must not need an extra request permit", results)
            assertEquals(4, bodies.get())
            assertEquals(4, directories.get())
            assertTrue(results!!.all { it.isOk })
        } finally {
            allBodies.complete(Unit)
            allDirectories.complete(Unit)
            requests.forEach { it.cancel() }
            withTimeout(5000) { requests.joinAll(); proxy.close() }
        }
    }
}
