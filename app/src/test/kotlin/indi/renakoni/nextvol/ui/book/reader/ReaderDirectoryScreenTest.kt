package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.MutableScrollContentUiSate
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ReaderDirectoryScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val content = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
        bookId = "book"
        readingChapterId = "a-80"
        contentList[1] = "a-80" to Ok(ChapterContentUiState("a-80", "Reading chapter", emptyList(), null, null))
    }
    private var volumes = BookVolumes("book", listOf(volume("a"), volume("b")))
    private val reader = MutableReaderScreenUiState(content).apply {
        bookId = "book"
        bookVolumes = Ok(volumes)
    }

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun teardown() { activity.pause().stop().destroy() }

    @Test fun directoryRefreshPreservesAnotherVolumeAndItsBrowsedChapterInTheReader() {
        show()
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed().assertIsSelected()
        directory().performScrollToIndex(0)
        compose.onNodeWithText("Volume a").performClick()
        compose.onNodeWithText("Volume b").performClick()
        directory().performScrollToIndex(51)
        compose.onNodeWithText("Chapter b-50").assertIsDisplayed()
        compose.runOnIdle {
            volumes = volumes.copy(volumes = volumes.volumes.map { volume ->
                volume.copy(chapters = listOf(ChapterInformation("${volume.volumeId}-new", "New prologue")) + volume.chapters)
            })
            reader.bookVolumes = Ok(volumes)
        }
        compose.onNodeWithText("Chapter b-50").assertIsDisplayed()
        compose.onNodeWithText("Chapter a-80").assertDoesNotExist()
        // A real chapter change still replaces the manual browsing position.
        compose.runOnIdle { content.readingChapterId = "a-90" }
        compose.onNode(hasText("Chapter a-90") and hasAnyAncestor(isDialog())).assertIsDisplayed().assertIsSelected()
    }

    private fun directory() = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))

    private fun show() {
        val settings = mockk<ReaderSettingsEditor>(relaxed = true) {
            every { paperId } returns "paper"
            every { fontFamilyUri } returns Uri.EMPTY
            every { fontSize } returns 15f
            every { fontWeigh } returns 500f
            every { reduceMotion } returns true
        }
        val fonts = mockk<ReaderFontFamilySettings> { every { getFlow() } returns flowOf(Uri.EMPTY) }
        compose.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(
                    LocalAppTheme provides AppTheme(false, lightColorScheme()),
                    LocalDensity provides Density(1f),
                ) {
                    MaterialTheme {
                        ReaderScreen(reader, settings, fonts, {}, { _, _ -> }, { _, _ -> }, {}, {},
                            { content.readingChapterId = it }, {}, ReadAloudState(), {}, {}, {}, {})
                    }
                }
            }
        }
        // Open controls through the real body gesture, then the real directory button.
        compose.onNode(hasScrollToIndexAction()).performTouchInput { click(center) }
        compose.onNodeWithContentDescription(activity.get().getString(R.string.detail_contents)).performClick()
    }

    private fun volume(id: String) = Volume(id, "Volume $id", (1..100).map {
        ChapterInformation("$id-$it", "Chapter $id-$it")
    })
}
