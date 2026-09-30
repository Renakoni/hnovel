package indi.renakoni.nextvol.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Trace
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.BuildConfig
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal object ReaderPerformanceStats {
    val requests = AtomicInteger()
    val directories = AtomicInteger()
    val active = AtomicInteger()
    val completed = AtomicInteger()
    val cancelled = AtomicInteger()
    @Volatile var readable = ""
    @Volatile var anchor = ""
    @Volatile var anchorPreserved: Boolean? = null
    @Volatile var action = "startup"
    @Volatile var error = ""
    @Volatile var contentVerified = false
    @Volatile var backgroundSubmittedNs = 0L
    @Volatile var backgroundWaitNs = 0L
    @Volatile var backgroundExecutionNs = 0L
    @Volatile var cancelRequestedNs = 0L
    @Volatile var cancelResponseNs = 0L
    fun reset() {
        requests.set(0); directories.set(0); active.set(0); completed.set(0); cancelled.set(0)
        readable = ""; anchor = ""; anchorPreserved = null; action = "startup"; error = ""
        contentVerified = false; backgroundSubmittedNs = 0; backgroundWaitNs = 0; backgroundExecutionNs = 0
        cancelRequestedNs = 0; cancelResponseNs = 0
    }
    fun json(context: Context) = JSONObject().apply {
        put("sha", BuildConfig.READER_BENCHMARK_SHA); put("variant", BuildConfig.BUILD_TYPE)
        put("benchmarkShortcuts", BuildConfig.BENCHMARK)
        put("profile", ReaderPerformanceFixture.profile(context))
        put("cache", ReaderPerformanceFixture.preferences(context).getString("cache", "unknown"))
        put("utf16PerChapter", ReaderPerformanceFixture.preferences(context).getInt("utf16PerChapter", 0))
        put("requests", requests.get()); put("directories", directories.get()); put("active", active.get())
        put("completed", completed.get()); put("cancelled", cancelled.get())
        put("readable", readable); put("anchor", anchor); put("anchorPreserved", anchorPreserved ?: JSONObject.NULL)
        put("action", action); put("error", error)
        put("contentVerified", contentVerified); put("backgroundWaitNs", backgroundWaitNs)
        put("backgroundExecutionNs", backgroundExecutionNs); put("cancelResponseNs", cancelResponseNs)
    }
}

@android.annotation.TargetApi(29)
internal class ReaderPerformanceSource(private val context: Context) : WebBookDataSource {
    override val id = ReaderPerformanceFixture.sourceId
    override val permits = 1
    override val offLine = false
    override val isOffLineFlow = MutableStateFlow(false)
    override suspend fun isOffLine() = false
    override val searchProvider: SearchProvider get() = error("Fixture does not support search")
    override val explorePageProvider: ExplorePageProvider get() = error("Fixture does not support discovery")
    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> =
        Err(WebRequestError("Unsupported", "Fixture supports only reading"))
    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> {
        ReaderPerformanceStats.directories.incrementAndGet()
        return Ok(BookVolumes(id, listOf(Volume("volume", "Reader fixture", listOf(
            ChapterInformation("1", "Reader chapter 1"), ChapterInformation("2", "Reader chapter 2"))))))
    }
    override suspend fun getChapterContent(chapterId: String, bookId: String): Result<ChapterContent, WebRequestError> {
        val cookie = ReaderPerformanceStats.requests.incrementAndGet()
        ReaderPerformanceStats.active.incrementAndGet()
        val startedNs = System.nanoTime()
        if (chapterId == "background") {
            ReaderPerformanceStats.backgroundWaitNs = startedNs - ReaderPerformanceStats.backgroundSubmittedNs
        }
        Trace.beginAsyncSection("reader.source.execute", cookie)
        try {
            // Bounded blocking native-source work exercises the real priority dispatcher.
            val until = SystemClock.elapsedRealtime() + if (chapterId == "background") 1_200 else 350
            while (SystemClock.elapsedRealtime() < until) {
                currentCoroutineContext().ensureActive()
                Thread.sleep(10)
            }
            ReaderPerformanceStats.completed.incrementAndGet()
            return Ok(ReaderPerformanceFixture.content(context, chapterId))
        } catch (cancelled: CancellationException) {
            ReaderPerformanceStats.cancelled.incrementAndGet()
            throw cancelled
        } finally {
            if (chapterId == "background") {
                ReaderPerformanceStats.backgroundExecutionNs = System.nanoTime() - startedNs
                if (ReaderPerformanceStats.cancelRequestedNs > 0) {
                    ReaderPerformanceStats.cancelResponseNs = System.nanoTime() - ReaderPerformanceStats.cancelRequestedNs
                }
            }
            Trace.endAsyncSection("reader.source.execute", cookie)
            ReaderPerformanceStats.active.decrementAndGet()
        }
    }
}

/** Available only in the separately identified, test-only application. */
class ReaderPerformanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == "seed") runBlocking {
                    ReaderPerformanceFixture.seed(context, intent.getStringExtra("profile") ?: "short",
                        intent.getStringExtra("cache") ?: "trusted")
                }
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = ReaderPerformanceStats.json(context).toString()
            } catch (failure: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "${failure.javaClass.simpleName}: ${failure.message}"
            } finally { pending.finish() }
        }.start()
    }
}
