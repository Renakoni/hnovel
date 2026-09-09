package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode

import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepositoryFixture
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repository and chapter loader; each concrete test loads only its own reading mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
abstract class CachedChapterReaderContractTest {
    internal val env = ModeTestEnvironment()
    private val fixture = BookRepositoryFixture()
    private val error = WebRequestError("offline", "request failed")

    protected abstract fun controller(loader: ReaderChapterLoader): ReaderModeController

    @After fun tearDown() = env.close()

    @Test
    fun delayedRefreshFailurePreservesDisplayedCacheWithoutRenderingOrWritingAgain() {
        val remote = CompletableDeferred<Unit>()
        val mode = openCachedChapter(remote)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals("processed:cached", content.title)
        val progress = mode.uiState.readingProgress

        remote.complete(Unit)
        env.runCurrent()

        assertSame(content, mode.uiState.readingChapterContent!!.get())
        assertEquals(progress, mode.uiState.readingProgress)
        assertEquals(1, env.records.writes.size)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("cached", "book", any()) }
        coVerify(exactly = 0) { fixture.local.updateChapterContent(any()) }
        verify(exactly = 1) { env.renderer.getContentDataFromJson(any()) }
    }

    @Test
    fun anUncachedChapterStillReplacesThePreviousChapterWithItsOwnError() {
        val remote = CompletableDeferred<Unit>()
        val mode = openCachedChapter(remote)
        remote.complete(Unit)
        env.runCurrent()
        coEvery { fixture.local.getChapterContent(BookIdentity.chapter("uncached", BookIdentity.book("book")).storageKey) } returns null
        coEvery { fixture.remote.getChapterContent("uncached", "book", any()) } returns Err(error)

        mode.changeChapter("uncached")
        env.runCurrent()

        assertEquals("uncached", mode.uiState.readingChapterId)
        assertEquals(Err(error), mode.uiState.readingChapterContent)
        assertEquals(1, env.records.writes.size)
        verify(exactly = 1) { env.renderer.getContentDataFromJson(any()) }
    }

    private fun openCachedChapter(remote: CompletableDeferred<Unit>): ReaderModeController {
        coEvery { fixture.local.getChapterContent(BookIdentity.chapter("cached", BookIdentity.book("book")).storageKey) } returns env.chapter("cached")
        coEvery { fixture.remote.getChapterContent("cached", "book", any()) } coAnswers {
            remote.await()
            Err(error)
        }
        every { fixture.text.processChapterContent(BookIdentity.bookKey("book"), any()) } answers {
            val chapter = secondArg<() -> ChapterContent>()()
            chapter.copy(title = "processed:${chapter.title}")
        }
        val mode = controller(ReaderChapterLoader(fixture.chapterRepository(), env.renderer))
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("cached")
        env.runCurrent()
        assertEquals("processed:cached", mode.uiState.readingChapterContent!!.get()!!.title)
        return mode
    }
}
