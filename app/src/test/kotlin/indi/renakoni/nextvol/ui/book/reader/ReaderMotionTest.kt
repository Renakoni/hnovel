package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.ui.book.reader.content.readerPageSwipe
import indi.renakoni.nextvol.ui.components.AnimatedTextLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
class ReaderMotionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun setUp() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun readerMotionChangesLocallyWithoutRecreatingContentAndRestoresOnExit() {
        var reduced by mutableStateOf(false)
        var outsideDuration = -1L
        var insideDuration = -1L
        var identity: Any? = null
        setContent {
            MaterialTheme {
                val outside = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                SideEffect { outsideDuration = TargetBasedAnimation(outside, Float.VectorConverter, 0f, 1f).durationNanos }
                ReaderMotionTheme(reduced) {
                    val inside = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                    val token = remember { Any() }
                    SideEffect {
                        identity = token
                        insideDuration = TargetBasedAnimation(inside, Float.VectorConverter, 0f, 1f).durationNanos
                    }
                    Text("Reader")
                }
            }
        }
        compose.waitForIdle()
        val originalIdentity = identity
        val originalDuration = insideDuration
        assertTrue(originalDuration > 0)
        compose.runOnIdle { reduced = true }
        compose.runOnIdle {
            assertEquals(0L, insideDuration)
            assertEquals(originalDuration, outsideDuration)
            assertSame(originalIdentity, identity)
            reduced = false
        }
        compose.runOnIdle {
            assertEquals(originalDuration, insideDuration)
            assertSame(originalIdentity, identity)
        }
    }

    @Test fun swipesTurnOnceRespectDirectionAndIgnoreCancelledOrDisabledGestures() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var enabled by mutableStateOf(true)
        val turns = mutableListOf<Boolean>()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(240.dp).testTag("page").readerPageSwipe(enabled) { turns += it })
            }
        }
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { down(centerRight); moveTo(centerLeft); cancel() }
        assertTrue(turns.isEmpty())
        page.performTouchInput { swipeLeft() }
        page.performTouchInput { swipeRight() }
        assertEquals(listOf(true, false), turns)
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        page.performTouchInput { swipeLeft() }
        page.performTouchInput { swipeRight() }
        assertEquals(listOf(true, false, false, true), turns)
        compose.runOnIdle { enabled = false }
        page.performTouchInput { swipeLeft() }
        assertEquals(4, turns.size)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun readingSheetAndTitleSettleWithoutAProlongedTransition() {
        var show by mutableStateOf(false)
        var title by mutableStateOf("Before")
        lateinit var state: SheetState
        lateinit var scope: CoroutineScope
        setContent {
            ReaderMotionTheme(true) {
                state = rememberBottomSheetState(initialValue = SheetValue.Hidden)
                scope = rememberCoroutineScope()
                Column {
                    AnimatedTextLine(title, animationEnabled = false)
                    if (show) ModalBottomSheet(onDismissRequest = { show = false }, sheetState = state) {
                        Box(Modifier.size(200.dp)) { Text("Reader panel") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Before").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { title = "After" }
        settleFrames()
        compose.onNodeWithText("After").assertIsDisplayed()
        compose.onNodeWithText("Before").assertDoesNotExist()
        compose.runOnIdle { show = true }
        settleFrames()
        compose.onNodeWithText("Reader panel").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.isVisible)
            assertFalse(state.isAnimationRunning)
            scope.launch { state.hide() }
        }
        settleFrames()
        compose.runOnIdle {
            assertEquals(SheetValue.Hidden, state.currentValue)
            assertFalse(state.isAnimationRunning)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { activity.get().setContent(content = content) }
    }

    private fun settleFrames() {
        // Android measure/layout runs outside the Compose clock, between animation frames.
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
