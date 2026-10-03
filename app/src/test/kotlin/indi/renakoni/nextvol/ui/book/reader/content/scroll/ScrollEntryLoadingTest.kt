package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
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
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollEntryLoadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val draws = mutableListOf<Triple<String, Boolean, Int>>()
    private val state = MutableScrollContentUiSate({}, {}, {}, {}, {},
        onProgressRestoring = { stateRestoring(true) },
        onProgressRestored = { stateRestoring(false) },
    ).apply {
        bookId = "book"
        readingChapterId = "current"
        readingProgress = 0.5f
        isRestoringProgress = true
    }
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } returns 16f
        every { fontWeigh } returns 400f
        every { reduceMotion } returns true
    }
    private val loading = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun stateRestoring(value: Boolean) { state.isRestoringProgress = value }

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = activity.get().window.decorView
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.width > 0 && view.height > 0) {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    // Child layers can record draw commands while hidden. Observe the pixels
                    // actually submitted by the whole view, rather than those child callbacks.
                    val chapter = when (bitmap.getPixel(160, 160)) {
                        android.graphics.Color.RED -> "previous"
                        android.graphics.Color.GREEN -> "current"
                        android.graphics.Color.BLUE -> "next"
                        else -> null
                    }
                    if (chapter != null) draws += Triple(chapter, state.isRestoringProgress,
                        state.lazyListState.firstVisibleItemScrollOffset)
                    bitmap.recycle()
                }
                return true
            }
        })
    }

    @After fun close() { activity.pause().stop().destroy() }

    @Test fun entryHasOneCenteredIndicatorUntilTheRequestedPositionIsReady() {
        mount()
        compose.onAllNodes(loading).assertCountEquals(1)
        val bounds = compose.onAllNodes(loading).fetchSemanticsNodes().single().boundsInRoot
        assertEquals(160f, bounds.center.x, 1f)
        assertEquals(160f, bounds.center.y, 1f)
        assertTrue(draws.isEmpty())

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            state.contentList[0] = "previous" to Ok(chapter("previous", next = "current"))
            state.contentList[1] = "current" to Ok(chapter("current", "previous", "next"))
            state.contentList[2] = "next" to Ok(chapter("next", prev = "current"))
        }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("Only one loading indicator may be exposed during entry",
                compose.onAllNodes(loading).fetchSemanticsNodes().size <= 1)
        }
        compose.mainClock.autoAdvance = true
        awaitBody()
        assertRestoredDraws()
        compose.onAllNodes(loading).assertCountEquals(0)
    }

    @Test fun firstDrawWithCachedNeighboursAlreadyUsesTheSavedPosition() {
        state.contentList[0] = "previous" to Ok(chapter("previous", next = "current"))
        state.contentList[1] = "current" to Ok(chapter("current", "previous", "next"))
        state.contentList[2] = "next" to Ok(chapter("next", prev = "current"))
        mount()
        awaitBody()
        assertRestoredDraws()
    }

    @Test fun pendingNeighboursDoNotDelayTheCurrentChapterOrShiftItWhenTheyArrive() {
        state.contentList[1] = "current" to Ok(chapter("current", "previous", "next"))
        mount()
        awaitBody()
        assertRestoredDraws()
        val offset = state.lazyListState.firstVisibleItemScrollOffset
        compose.runOnIdle {
            draws.clear()
            state.contentList[0] = "previous" to Ok(chapter("previous", next = "current"))
            state.contentList[2] = "next" to Ok(chapter("next", prev = "current"))
        }
        compose.waitForIdle()
        assertEquals(offset, state.lazyListState.firstVisibleItemScrollOffset)
        assertRestoredDraws()
    }

    private fun mount() {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme),
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings)) {
                        Box(Modifier.size(320.dp)) {
                            ScrollContentComponent(Modifier, state, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) }, PaddingValues(0.dp), {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitBody() {
        compose.waitUntil(10_000) { compose.waitForIdle(); !state.isRestoringProgress && draws.isNotEmpty() }
    }

    private fun assertRestoredDraws() {
        assertTrue("Must observe actual content draws", draws.isNotEmpty())
        assertTrue("Entry drew an adjacent chapter or an unrestored position: $draws",
            draws.all { (chapter, restoring, offset) -> chapter == "current" && !restoring && offset == 680 })
    }

    private fun chapter(id: String, prev: String? = null, next: String? = null) =
        ChapterContentUiState(id, id, listOf(Body(id)), prev, next)

    private inner class Body(private val chapter: String) : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) {
            val color = when (chapter) { "previous" -> Color.Red; "next" -> Color.Blue; else -> Color.Green }
            Box(Modifier.fillMaxWidth().height(2000.dp).background(color)) { Text(chapter) }
        }
    }
}
