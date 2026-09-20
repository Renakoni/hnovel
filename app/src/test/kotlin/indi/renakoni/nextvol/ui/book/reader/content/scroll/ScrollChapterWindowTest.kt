package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Navigation invalidates every subscription and any delayed settings or metadata work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ScrollChapterWindowTest {
    private val env = ModeTestEnvironment()
    private val enabled = MutableStateFlow(true)
    private val settingsGates = ArrayDeque<CompletableDeferred<Unit>>()
    private val mode = ScrollReaderController(
        env.loader, env.records, env.scope,
        object : ContinuousScrollSettings {
            override fun getFlow() = enabled
            override suspend fun isEnabled(): Boolean {
                settingsGates.removeFirstOrNull()?.await()
                return enabled.value
            }
        },
        { _, _ -> }, env.dispatcher, env.dispatcher,
    )

    @After fun close() = env.close()

    private fun openThird() {
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("3")
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", "2", "4", "BODY_3")))
    }

    @Test fun oldCurrentCannotPublishDuringSettingsRead() {
        openThird()
        val gate = CompletableDeferred<Unit>()
        settingsGates += gate
        mode.changeChapter("4")
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", "2", "4", "BODY_3_REFRESH")))
        assertEquals("4", mode.requestedChapterId)
        assertNull(mode.uiState.contentList[1])
        assertEquals("3", env.records.data.lastReadChapterId)
        assertNull(mode.uiState.readingChapterContent)
        gate.complete(Unit)
        env.runCurrent()
    }

    @Test fun failedExplicitNextRequestHasOneChapterKey() {
        openThird()
        mode.changeChapter("4")
        env.runCurrent()
        env.emit("4", Err(WebRequestError("offline", "request failed")))
        assertEquals(listOf("4"), mode.uiState.contentList.mapNotNull { it?.first })
        assertEquals(1, env.chapters.active.count { it.chapterId == "4" })
        assertEquals("3", env.records.data.lastReadChapterId)
    }

    @Test fun earlierSettingsReadCannotCancelTheNewerChapterRequest() {
        openThird()
        val gate = CompletableDeferred<Unit>()
        settingsGates += gate
        mode.changeChapter("4")
        env.runCurrent()
        mode.changeChapter("5")
        env.runCurrent()
        env.emit("5", Ok(env.chapter("5", "4", "6")))
        gate.complete(Unit)
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        assertEquals("5", mode.requestedChapterId)
        assertEquals("5", mode.uiState.contentList[1]!!.first)
        assertEquals("5", env.records.data.lastReadChapterId)
    }

    @Test fun cancelledOlderMetadataWriteCannotOverwriteTheNewerChapter() {
        openThird()
        val gate = CompletableDeferred<Unit>()
        env.records.nonCancellableWriteGates += gate
        env.emit("3", Ok(env.chapter("3", "2", "4", "REFRESH_3")))
        mode.changeChapter("4")
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        assertNull(mode.uiState.contentList[1])
        gate.complete(Unit)
        env.runCurrent()
        assertEquals("4", mode.requestedChapterId)
        assertEquals("4", env.records.data.lastReadChapterId)
    }

    @Test fun changingBookInvalidatesOldContentBeforeTheNextCommand() {
        openThird()
        env.events.clear()
        mode.changeBookId("other-book")
        env.emit("3", Ok(env.chapter("3", "2", "4", "OLD_BOOK")))
        assertFalse(env.events.contains("write/start/other-book"))
        assertEquals("book", env.chapters.preloads.last().bookId)
        assertEquals(listOf(null, null, null), mode.uiState.contentList.toList())
        assertNull(mode.requestedChapterId)
    }

    @Test fun retryOfTheSameChapterInvalidatesItsPendingSettingsRead() {
        openThird()
        val gate = CompletableDeferred<Unit>()
        settingsGates += gate
        mode.changeChapter("4")
        env.runCurrent()
        mode.uiState.retryChapter("4")
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4", "3", "5", "RETRIED_4")))
        gate.complete(Unit)
        env.runCurrent()
        assertEquals("RETRIED_4", env.records.data.lastReadChapterTitle)
        assertEquals(1, env.chapters.active.count { it.chapterId == "4" })
        assertEquals(listOf("4"), mode.uiState.contentList.mapNotNull { it?.first })
    }

    @Test fun closingTheModeDuringMetadataWriteCannotUpdateTheResumeChapter() {
        openThird()
        val gate = CompletableDeferred<Unit>()
        env.records.nonCancellableWriteGates += gate
        mode.changeChapter("4")
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        env.close()
        gate.complete(Unit)
        env.runCurrent()
        assertEquals("3", env.records.data.lastReadChapterId)
        assertTrue(env.chapters.active.isEmpty())
    }
}
