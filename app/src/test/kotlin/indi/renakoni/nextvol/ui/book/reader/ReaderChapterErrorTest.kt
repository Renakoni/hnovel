package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.MutableFlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.MutableScrollContentUiSate
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentComponent
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
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
class ReaderChapterErrorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val error = WebRequestError("Offline", "Reconnect and retry")

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun errorShowsKnownChapterAndOffersRetry() {
        var retries = 0
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme { ChapterContentError(error, "Chapter four") { retries++ } }
            }
        }
        compose.onNodeWithText("Chapter four").assertIsDisplayed()
        compose.onNodeWithText("Offline").assertIsDisplayed()
        compose.onNodeWithText("Reconnect and retry").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun flipErrorRetriesTheRequestedChapter() {
        val requested = mutableListOf<String>()
        val state = MutableFlipPageContentUiState({}, {}, { requested += it }, {}).apply {
            readingChapterId = "4"
            readingChapterContent = Err(error)
        }
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    FlipPageContentComponent(Modifier, state, mockk(), PaddingValues(0.dp), {}, {}, {},
                        chapterTitle = { "Chapter $it" })
                }
            }
        }
        compose.onNodeWithText("Chapter 4").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.action_retry)).performClick()
        assertEquals(listOf("4"), requested)
    }

    @Test fun loadingAndFailedTitlesFollowDirectoryIdentityAndSuccessUsesRenderedTitle() {
        val content = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply { readingChapterId = "4" }
        val screen = MutableReaderScreenUiState(content).apply {
            bookVolumes = Ok(BookVolumes("book", listOf(Volume("v", "Volume", listOf(
                ChapterInformation("3", "Third"), ChapterInformation("4", "Fourth"),
            )))))
        }
        assertEquals("Fourth", screen.chapterTitle("4"))
        content.contentList[1] = "4" to Err(error)
        assertEquals("Fourth", screen.chapterTitle("4"))
        content.contentList[1] = "4" to Ok(ChapterContentUiState("4", "Formatted fourth", emptyList(), "3", "5"))
        assertEquals("Formatted fourth", screen.chapterTitle("4"))
        assertEquals("Third", screen.chapterTitle("3"))
        assertNull(screen.chapterTitle("unknown"))
        assertNull(screen.chapterTitle(null))
    }

    @Test fun scrollErrorUsesRetryInsteadOfExplicitChapterNavigation() {
        val opened = mutableListOf<String>()
        val retried = mutableListOf<String>()
        val state = MutableScrollContentUiSate({}, {}, { opened += it }, {}, {}, { retried += it }).apply {
            readingChapterId = "4"
            contentList[1] = "4" to Err(error)
        }
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    ScrollContentComponent(Modifier, state, mockk(relaxed = true), mockk(),
                        PaddingValues(0.dp), {}, {}, {}, chapterTitle = { "Chapter $it" })
                }
            }
        }
        compose.onNodeWithText("Chapter 4").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.action_retry)).performClick()
        assertEquals(listOf("4"), retried)
        assertTrue(opened.isEmpty())
        compose.runOnIdle {
            assertEquals(false, state.lazyListState.layoutInfo.visibleItemsInfo.first { it.key == "4" }.contentType)
        }
    }

    @Test fun longErrorRemainsScrollableWhileSuccessfulProgressRestorationIsPending() {
        val state = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
            readingChapterId = "4"
            isRestoringProgress = true
            contentList[1] = "4" to Err(WebRequestError("Offline", List(30) { "Error details $it" }.joinToString("\n")))
        }
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    Box(Modifier.height(320.dp)) {
                        ScrollContentComponent(Modifier, state, mockk(relaxed = true), mockk(),
                            PaddingValues(0.dp), {}, {}, {}, chapterTitle = { "Chapter $it" })
                    }
                }
            }
        }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(state.lazyListState.firstVisibleItemScrollOffset > 0)
    }
}
