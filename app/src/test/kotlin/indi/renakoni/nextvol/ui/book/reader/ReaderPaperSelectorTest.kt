package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPaperSelectorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var selected by mutableStateOf(ReaderPaper.Default.id)
    private var dark by mutableStateOf(false)
    private val base = mockk<ReaderSettingsEditor>(relaxed = true)
    private val settings = object : ReaderSettingsEditor by base {
        override val paperId get() = selected
        override val enableBackgroundImage = false
        override val backgroundColor = Color.Unspecified
        override val backgroundDarkColor = Color.Unspecified
        override val textColor = Color.Unspecified
        override val textDarkColor = Color.Unspecified
        override val backgroundImageUri = Uri.EMPTY
        override val backgroundDarkImageUri = Uri.EMPTY
    }
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        every { settings.paperIdUserData.asynchronousSet(any()) } answers { selected = firstArg() }
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun show(fontScale: Float = 1f, inReader: Boolean = false) {
        activity.get().setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalAppTheme provides AppTheme(dark, scheme),
                    LocalDensity provides Density(1f, fontScale)) {
                    if (inReader) ReaderPaperTheme(settings) { ReaderPaperPage(settings, onBack = {}) }
                    else ReaderPaperPage(settings, onBack = {})
                }
            }
        }
    }

    @Test fun readingSamplesKeepSimpleLabelsAndPreserveRadioSelection() {
        show()
        compose.onNodeWithTag("reader-paper-default").assertIsSelected()
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Clear").assertIsDisplayed()
        compose.onNodeWithTag("reader-paper-inkwash").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Ink").assertIsDisplayed()
        compose.onNodeWithTag("reader-paper-default").assertIsNotSelected()
        verify(exactly = 1) { settings.paperIdUserData.asynchronousSet(ReaderPaper.Ink.id) }
    }

    @Test fun largeTextKeepsEveryLabelAndSelectionReachable() {
        show(fontScale = 1.8f)
        ReaderPaper.entries.forEach { paper ->
            compose.onNodeWithTag("reader-paper-${paper.id}").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText(activity.get().getString(paper.label)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun defaultSampleUsesTheActualLightAndDarkReadingBackground() {
        show()
        fun background() = compose.onNodeWithTag("reader-paper-preview-default", useUnmergedTree = true)
            .captureToImage().toPixelMap().let { it[4, it.height / 2] }
        assertEquals(lightColorScheme().background, background())
        compose.runOnIdle { dark = true }
        assertEquals(darkColorScheme().background, background())
    }

    @Test fun nightPaperDoesNotOverrideTheDefaultSampleInsideTheReader() {
        selected = ReaderPaper.Night.id
        show(inReader = true)
        val pixels = compose.onNodeWithTag("reader-paper-preview-default", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        assertEquals(lightColorScheme().background, pixels[4, pixels.height / 2])
    }
}
