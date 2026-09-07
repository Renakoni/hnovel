package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.LocalReaderSelectionState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderSelectionState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readerTapGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(ExperimentalFoundationApi::class)
class ReaderInteractionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: ActivityController<ComponentActivity>
    private val selection = ReaderSelectionState()
    private val toolbar = RecordingTextToolbar()
    private var originalContextMenuFlag = false
    private var readerTaps = 0

    @Before
    fun setUp() {
        // Exercise the native text-toolbar path where the transient Select all menu was reported.
        originalContextMenuFlag = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        WindowCompat.setDecorFitsSystemWindows(activity.get().window, false)
        activity.setup()
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
        ComposeFoundationFlags.isNewContextMenuEnabled = originalContextMenuFlag
    }

    @Test
    fun dismissSelectionDoesNotReopenToolbarOrTriggerReaderTap() {
        setContent {
            Column(Modifier.fillMaxSize().readerTapGestures { readerTaps++ }) {
                ReaderText("first")
                ReaderText("second")
                Box(Modifier.fillMaxSize().testTag("blank"))
            }
        }

        selectFirstWord()
        val showsBeforeDismiss = compose.runOnIdle {
            assertTrue(selection.hasSelection)
            assertEquals(0, readerTaps)
            assertTrue(toolbar.showCount > 0)
            toolbar.showCount
        }
        compose.onNodeWithTag("blank").performTouchInput { click() }
        compose.runOnIdle {
            assertFalse(selection.hasSelection)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
            assertEquals(showsBeforeDismiss, toolbar.showCount)
            assertEquals(0, readerTaps)
        }
        compose.onNodeWithTag("blank").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, readerTaps) }
    }

    @Test
    fun tappingOtherTextClearsSelectionBeforeTextHandlesRelease() {
        setContent {
            Column(Modifier.fillMaxSize().readerTapGestures { readerTaps++ }) {
                ReaderText("first")
                ReaderText("second")
            }
        }
        selectFirstWord()
        val showsBeforeDismiss = compose.runOnIdle { toolbar.showCount }
        compose.onNodeWithTag("second").performTouchInput { click(Offset(24f, 20f)) }
        compose.runOnIdle {
            assertFalse(selection.hasSelection)
            assertEquals(showsBeforeDismiss, toolbar.showCount)
            assertEquals(0, readerTaps)
        }
    }

    @Test
    fun scrollingAndLongPressDoNotOpenReaderMenu() {
        var scrollOffset = { 0 }
        setContent {
            val scrollState = rememberScrollState()
            scrollOffset = { scrollState.value }
            Column(
                Modifier.fillMaxSize().testTag("scroll")
                    .readerTapGestures { readerTaps++ }
                    .verticalScroll(scrollState)
            ) {
                repeat(20) { ReaderText(if (it == 0) "first" else "text-$it") }
            }
        }
        selectFirstWord()
        compose.runOnIdle { assertTrue(selection.hasSelection) }
        compose.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(scrollOffset() > 0)
            assertEquals(0, readerTaps)
        }
    }

    @Test
    fun systemBarVisibilityDoesNotChangeReaderViewport() {
        lateinit var view: View
        setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().padding(readerAutoPadding(40.dp))) {
                Box(Modifier.fillMaxSize().testTag("viewport"))
            }
        }
        fun dispatchBars(visible: Boolean) {
            compose.runOnIdle {
                val bars = WindowInsetsCompat.Type.systemBars()
                val stableInsets = Insets.of(0, 24, 0, 48)
                ViewCompat.dispatchApplyWindowInsets(
                    view,
                    WindowInsetsCompat.Builder()
                        .setInsetsIgnoringVisibility(bars, stableInsets)
                        .setInsets(bars, if (visible) stableInsets else Insets.NONE)
                        .setVisible(bars, visible)
                        .build()
                )
            }
        }
        dispatchBars(false)
        val hiddenBounds = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        dispatchBars(true)
        assertEquals(hiddenBounds, compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot())
        dispatchBars(false)
        assertEquals(hiddenBounds, compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot())
    }

    private fun selectFirstWord() {
        compose.onNodeWithTag("first").performTouchInput { longClick(Offset(24f, 20f)) }
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(
                    LocalReaderSelectionState provides selection,
                    LocalTextToolbar provides toolbar,
                    content = content
                )
            }
        }
    }

    @Composable
    private fun ReaderText(tag: String) {
        SimpleTextComponentContent(
            modifier = Modifier.fillMaxWidth().height(100.dp).testTag(tag),
            text = "Reader selection should stay separate from menu taps.",
            fontSize = 18.sp,
            fontLineHeight = 4.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = null,
            color = Color.Black
        )
    }

    private class RecordingTextToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var showCount = 0

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
        ) {
            showCount++
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }
}
