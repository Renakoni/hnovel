package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderChapterLoaderTest {
    private val env = ModeTestEnvironment()
    @After fun tearDown() = env.close()

    @Test
    fun eachCollectorMapsIndependentlyAndPreservesComponentsAndOrderedErrors() {
        val component = mockk<AbstractContentComponent<*>>()
        every { env.renderer.getContentDataFromJson(any()) } returns ContentData(listOf(component))
        val first = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val second = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val flow = env.loader.load("request", "book")
        assertTrue(env.chapters.active.isEmpty())
        val firstJob = env.scope.launch { flow.collect(first::add) }
        env.scope.launch { flow.collect(second::add) }
        env.runCurrent()
        assertEquals(2, env.chapters.active.size)
        env.emit("request", Ok(env.chapter("payload", "prev", "next", "Title")))
        val error = Err(WebRequestError("offline", "remote failed"))
        env.emit("request", error)
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertSame(component, first.first().get()!!.content.single())
        assertEquals(error, first.last())
        assertEquals(error, second.last())
        firstJob.cancel()
        env.runCurrent()
        assertEquals(1, env.chapters.active.size)
        env.emit("request", Ok(env.chapter("later")))
        assertEquals(2, first.size)
        assertEquals(3, second.size)
        assertTrue(env.records.writes.isEmpty())
        assertTrue(env.chapters.preloads.isEmpty())
    }

    @Test
    fun rendererFailuresTerminateTheSubscriptionWithoutInventingAFallback() {
        val failure = IllegalArgumentException("invalid component JSON")
        every { env.renderer.getContentDataFromJson(any()) } throws failure
        var caught: Throwable? = null
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch {
            env.loader.load("request", "book").catch { caught = it }.collect(results::add)
        }
        env.runCurrent()
        env.emit("request", Ok(env.chapter("request")))
        assertSame(failure, caught)
        assertTrue(results.isEmpty())
        assertTrue(env.chapters.active.isEmpty())
    }
}
