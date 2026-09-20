package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTextWindowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var scope: CoroutineScope
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } returns 15f
        every { fontLineHeight } returns 7f
        every { fontWeigh } returns 500f
        every { paragraphSpacing } returns 4f
        every { reduceMotion } returns true
    }
    private val state: MutableScrollContentUiSate = MutableScrollContentUiSate({}, {}, {}, {}, {},
        onProgressRestored = { state.isRestoringProgress = false })

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun restoredLongChapterOnlyComposesNearbyTextAndKeepsItsFullGeometry() {
        mount(0.5f)
        awaitBody()
        val info = state.lazyListState.layoutInfo
        val item = info.visibleItemsInfo.single { it.key == "chapter" }
        assertTrue(item.size > info.viewportSize.height * 10)
        assertEquals(0.5f, (-item.offset + info.viewportSize.height).toFloat() / item.size, 0.01f)
        assertBoundedText()
        compose.onNodeWithText("PARAGRAPH_001", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("PARAGRAPH_200", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, 0) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_001"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("PARAGRAPH_001", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PARAGRAPH_200", useUnmergedTree = true).assertDoesNotExist()
        assertBoundedText()
    }

    @Test fun scrollingToTheEndAndBackDoesNotLeaveAnEmptyTextWindow() {
        mount(0f)
        awaitBody()
        val height = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, height) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_200"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertBoundedText()
        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, height / 2) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_100"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertBoundedText()
    }

    @Test fun oneVeryLongParagraphIsAlsoLimitedToNearbyFragments() {
        val text = (1..4_000).joinToString(" ") { "word$it" }
        mount(0.5f, text)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !state.isRestoringProgress
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("word", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val visibleText = compose.onAllNodes(hasText("word", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString("") { it.text }
        assertTrue(visibleText.length in 1 until text.length / 3)
        val info = state.lazyListState.layoutInfo
        assertTrue(info.visibleItemsInfo.single { it.key == "chapter" }.size > info.viewportSize.height * 10)
        assertFalse(visibleText.contains("word1 "))
        assertFalse(visibleText.contains("word4000"))
    }

    @Test fun reopeningTheSameChapterWithNewContentRestoresUsingItsNewHeight() {
        mount(0.5f)
        awaitBody()
        val oldHeight = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        val replacement = SimpleTextComponent(SimpleTextComponentData(
            (1..400).joinToString("\n") { "PARAGRAPH_%03d".format(it) }), mockk(relaxed = true), activity.get())
        compose.runOnIdle {
            state.contentList[1] = "chapter" to Ok(ChapterContentUiState("chapter", "Chapter", listOf(replacement), null, null))
            state.readingProgress = 0.5f
            state.isRestoringProgress = true
            state.lazyListState = LazyListState()
        }
        awaitBody()
        val info = state.lazyListState.layoutInfo
        val item = info.visibleItemsInfo.single { it.key == "chapter" }
        assertTrue(item.size > oldHeight * 1.9f)
        assertEquals(0.5f, (-item.offset + info.viewportSize.height).toFloat() / item.size, 0.01f)
        assertBoundedText()
    }

    private fun assertBoundedText() {
        val count = compose.onAllNodes(hasText("PARAGRAPH_", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("Only the viewport and nearby text should be composed, found $count", count in 1..65)
    }

    private fun awaitBody() {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !state.isRestoringProgress
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun mount(progress: Float, text: String = (1..200).joinToString("\n") { "PARAGRAPH_%03d".format(it) }) {
        val component = SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get())
        state.bookId = "book"
        state.readingChapterId = "chapter"
        state.readingProgress = progress
        state.isRestoringProgress = true
        state.contentList[1] = "chapter" to Ok(ChapterContentUiState("chapter", "Chapter", listOf(component), null, null))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme),
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings)) {
                        Box(Modifier.width(320.dp).height(320.dp)) {
                            ScrollContentComponent(Modifier, state, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) }, PaddingValues(0.dp), {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
