package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextDrawObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
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
class ScrollTextFirstDrawTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val prepared = mutableStateOf(chapter())
    private val offset = mutableStateOf(0)
    private val draws = mutableListOf<Set<Int>>()
    private val drawnFragments = mutableSetOf<Int>()

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = activity.get().window.decorView
        // Robolectric does not submit hardware frames. Draw at each actual pre-draw boundary,
        // before a layout-triggered state write can be consumed by a later recomposition.
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.width > 0 && view.height > 0) {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    bitmap.recycle()
                }
                return true
            }
        })
    }
    @After fun close() { activity.pause().stop().destroy() }

    private fun mount() {
        activity.get().setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReaderTextDrawObserver provides { fragment, _, _ ->
                    drawnFragments += fragment.start
                }) {
                    Box(Modifier.size(240.dp).clipToBounds().drawWithContent {
                        drawnFragments.clear()
                        drawContent()
                        draws += drawnFragments.toSet()
                    }) {
                        // Move only the ancestor placement, as LazyColumn's scroll-only path does.
                        Layout(content = { ScrollTextContent(prepared.value, Color.Black, Modifier) }) { children, constraints ->
                            val child = children.single().measure(Constraints.fixedWidth(constraints.maxWidth))
                            layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, -offset.value) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun firstDrawAlreadyContainsText() {
        mount()
        assertEveryDrawHasText()
    }

    @Test fun replacementGeometryDoesNotDrawAnEmptyFrame() {
        mount()
        compose.runOnIdle { draws.clear(); prepared.value = chapter() }
        compose.waitForIdle()
        assertEveryDrawHasText()
    }

    @Test fun anOffscreenComponentHasTextOnItsFirstVisibleDraw() {
        offset.value = -4_000
        mount()
        compose.runOnIdle { draws.clear(); offset.value = 0 }
        compose.waitForIdle()
        assertEveryDrawHasText()
    }

    @Test fun largeAncestorPlacementJumpsComposeTheDestinationBeforeDrawing() {
        mount()
        for (destination in listOf(8_000, 16_000, 0)) {
            compose.runOnIdle { draws.clear(); offset.value = destination }
            compose.waitForIdle()
            assertEveryDrawHasText()
            assertTrue("Far-away fragments should stay uncomposed", draws.all { it.size < 40 })
            assertTrue("The first draw must contain the destination fragment",
                draws.first().contains(destination / 100 * 10))
        }
    }

    private fun assertEveryDrawHasText() {
        assertTrue("The test must observe real draw callbacks", draws.isNotEmpty())
        assertTrue("Visible text was absent in draw(s): $draws", draws.all { it.isNotEmpty() })
    }

    companion object {
        private fun chapter() = ScrollTextLayout((0 until 200).map { index ->
            ReaderTextFragment(0, index * 10, index * 10 + 10, "TEXT_$index", 0, 100,
                listOf(index * 10), listOf(0))
        }, TextStyle(fontSize = 16.sp, lineHeight = 20.sp))
    }
}
