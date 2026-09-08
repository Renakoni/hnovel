package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode

import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderMode
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeController
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeHost
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderModeHostTest {
    private val events = mutableListOf<String>()
    private val created = mutableListOf<Controller>()
    private val host = ReaderModeHost { mode ->
        events += "create/$mode"
        Controller(mode.name).also(created::add)
    }

    @Test
    fun selectionBindsBookThenChapterAndIdenticalModeEmissionsDoNothing() {
        assertTrue(host.select(ReaderMode.Scroll, { events += "read/book"; "book" }, { events += "read/chapter"; "chapter" }))
        assertSame(created.single().uiState, host.uiState)
        assertEquals(listOf("create/Scroll", "read/book", "Scroll/book/book", "read/chapter", "Scroll/chapter/chapter"), events)
        events.clear()
        assertFalse(host.select(ReaderMode.Scroll, { error("unexpected read") }, { error("unexpected read") }))
        assertTrue(events.isEmpty())
    }

    @Test
    fun replacementAndCommandsTargetTheSelectedControllerWithoutCommandsToTheOldOne() {
        host.select(ReaderMode.Scroll, { "book" }, { "chapter" })
        val old = created.single()
        events.clear()
        host.select(ReaderMode.Flip, { "book" }, { "chapter" })
        host.changeBookId("other")
        host.changeChapter("direct")
        host.loadNextChapter()
        host.loadPrevChapter()
        assertEquals(listOf("create/Flip", "Flip/book/book", "Flip/chapter/chapter", "Flip/book/other", "Flip/chapter/direct", "Flip/next", "Flip/prev"), events)
        assertSame(created.last().uiState, host.uiState)
        assertFalse(old === created.last())
    }

    @Test
    fun sessionValuesAreReadAfterConstructionAndBetweenBindingCalls() {
        var book = "before"
        var chapter = "before"
        val host = ReaderModeHost {
            book = "after construction"
            object : ReaderModeController {
                override val uiState = mockk<ContentUiState>()
                override fun changeBookId(id: String) { events += id; chapter = "after book binding" }
                override fun changeChapter(id: String) { events += id }
                override fun loadNextChapter() = Unit
                override fun loadPrevChapter() = Unit
            }
        }
        host.select(ReaderMode.Flip, { book }, { chapter })
        assertEquals(listOf("after construction", "after book binding"), events)
    }

    @Test
    fun commandsBeforeInitialSelectionAreNoOpsAndReturningToAModeCreatesAFreshController() {
        host.changeBookId("ignored")
        host.changeChapter("ignored")
        host.loadNextChapter()
        host.loadPrevChapter()
        assertTrue(events.isEmpty())
        host.select(ReaderMode.Scroll, { "book" }, { "chapter" })
        host.select(ReaderMode.Flip, { "book" }, { "chapter" })
        host.select(ReaderMode.Scroll, { "book" }, { "chapter" })
        assertEquals(3, created.size)
        assertFalse(created.first() === created.last())
    }

    private inner class Controller(private val name: String) : ReaderModeController {
        override val uiState = mockk<ContentUiState>()
        override fun changeBookId(id: String) { events += "$name/book/$id" }
        override fun changeChapter(id: String) { events += "$name/chapter/$id" }
        override fun loadNextChapter() { events += "$name/next" }
        override fun loadPrevChapter() { events += "$name/prev" }
    }
}
