package indi.renakoni.nextvol.ui.book.download

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.data.download.DownloadFailure
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BookDownloadScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var state by mutableStateOf(BookDownloadUiState("test-book"))
    private var submitted = 0
    private var reloads = 0

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        activity.get().setContent { MaterialTheme {
            BookDownloadScreen(state, {}, { reloads++ }, { state = state.copy(selected = it) },
                { state = state.copy(refresh = it) }, { submitted++ }, {}, {})
        } }
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun directoryLoadingAndFailureCannotSubmitAndFailureOffersReload() {
        compose.onNodeWithText("Download 0 chapters").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, directoryFailure = DownloadFailure.Network) }
        compose.onNodeWithText("Download 0 chapters").assertIsNotEnabled()
        compose.onNodeWithText("Reload directory").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reloads); assertEquals(0, submitted) }
    }

    @Test fun selectionRequiresConfirmationAndSupportsWholeVolumeAndChapterRange() {
        val chapters = (1..3).map { ChapterInformation("chapter-$it", "Chapter $it") }
        compose.runOnIdle { state = state.copy(loading = false, volumes = BookVolumes("test-book", listOf(Volume("v1", "Volume One", chapters)))) }
        compose.onNodeWithText("All", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, state.selected.size); assertEquals(0, submitted) }
        compose.onNodeWithTag("download-volume-0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, state.selected.size) }
        compose.onNodeWithTag("download-volume-0").performClick()
        compose.runOnIdle { assertEquals(3, state.selected.size); assertEquals(0, submitted) }
        compose.onNodeWithText("Chapter range").performScrollTo().performClick()
        compose.onNodeWithText("First chapter").performTextReplacement("2")
        compose.onNodeWithText("Last chapter").performTextReplacement("2")
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(setOf("chapter-2"), state.selected); assertEquals(0, submitted) }
        compose.onNodeWithText("Download 1 chapters").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun rangeIsInclusiveAndRejectsInvalidInput() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(setOf("b", "c"), downloadRange(ids, 2, 3))
        assertNull(downloadRange(ids, 0, 3))
        assertNull(downloadRange(ids, 3, 2))
        assertNull(downloadRange(ids, 1, 5))
        assertNull(downloadRange(ids, null, 2))
    }
}
